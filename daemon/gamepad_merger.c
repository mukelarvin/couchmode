// gamepad_merger — CouchMode's root/shell daemon.
//
// Keeps a persistent uinput virtual gamepad and forwards events 1:1 from the
// highest-priority *connected* physical controller in a priority list (devices
// are matched by NAME plus bus:vendor:product:version). The source is grabbed
// so apps only see the virtual device. When a higher-priority controller
// appears the daemon switches to it; when the active one disappears it falls
// back down the list. The list is saved to CONFIG_PATH so it survives restarts.
//
// The app controls the daemon over an abstract unix socket ("@couchmode"),
// line-based text protocol, one request per line:
//
//   PING                  -> PONG
//   STATUS                -> S<TAB>activeOrTopName<TAB>attached(0/1)<TAB>virtual<TAB>id<TAB>decoy(0/1)<TAB>uniq
//   LIST                  -> D<TAB>name<TAB>bus:vendor:product:version<TAB>isSource(0/1)<TAB>uniq
//                            ... then END       (gamepad-looking devices only)
//   PRIORITY[<TAB>name<TAB>id<TAB>uniq]... -> OK   replace the priority list (up to 8
//                            name/id/uniq triples, highest first). id is
//                            "bus:vendor:product:version" (hex); uniq is the device's unique
//                            string (the Bluetooth address for a BT pad). Each is empty = match
//                            any. id and uniq tell same-named devices apart: a pad and the
//                            vendor's copy of it, or two identical pads.
//   GETPRIO               -> P<TAB>name<TAB>id<TAB>uniq<TAB>connected(0/1)<TAB>active(0/1)
//                            ... then END
//   SOURCE <name>[<TAB>id[<TAB>uniq]] -> OK    shorthand for a one-entry PRIORITY
//   SNIFF <name>[<TAB>id[<TAB>uniq]] -> OK|ERR ...  then "E type code value" lines for
//                            every EV_KEY/EV_ABS event; "GONE" if the device
//                            disappears or (for the active source) we switch away. Works on the current source too (a
//                            grabbed device can't be opened twice, so we tap
//                            our own forwarding for it).
//   STOP                  -> OK     stop sniffing
//   DECOY 0|1             -> OK     destroy/create the decoy gamepad (see create_decoy); saved
//   RECREATE              -> OK     destroy and re-create the virtual gamepad (new device, same
//                            source). Needed after the Retroid service's ignore list changes,
//                            because it only consults that list when a device appears.
//
// Usage: gamepad_merger [-t] [-u app_uid] [-s "source name"] [-n "virtual name"]
//   -t  log the latency we add every 10s
//   -u  only accept socket clients from this uid (plus root and shell)
//
// Match devices by NAME, never by event number (changes per boot) and never
// by VID:PID alone (the "Retroid Pocket Virtual Mouse" shares 2022:3001).

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define DEFAULT_SOURCE_NAME "Retroid Pocket Controller"
#define DEFAULT_VIRTUAL_NAME "CouchMode Virtual Gamepad"
#define DECOY_NAME "CouchMode Decoy (ignore)"
#define SOCKET_NAME "couchmode"
// Stable identity for the virtual device (pid.codes test VID, arbitrary PID).
#define VIRTUAL_VENDOR 0x1209
#define VIRTUAL_PRODUCT 0xC0DE
#define VIRTUAL_VERSION 1
#define RESCAN_INTERVAL_MS 1000
#define MAX_CLIENTS 4
#define MAX_PRIO 8
#define CONFIG_PATH "/data/local/tmp/couchmode-priority.conf"
#define NAME_LEN 256
#define UID_SHELL 2000

#define BITS_PER_LONG (sizeof(unsigned long) * 8)
#define NLONGS(x) (((x) + BITS_PER_LONG - 1) / BITS_PER_LONG)
#define TEST_BIT(bit, arr) ((arr)[(bit) / BITS_PER_LONG] & (1UL << ((bit) % BITS_PER_LONG)))

static volatile sig_atomic_t g_stop = 0;
static int g_stats = 0;            // -t
static int g_allowed_uid = -1;     // -u; -1 = accept any client
struct prio_entry {
    char name[NAME_LEN];
    char id[32];  // "bus:vendor:product:version" (hex) to tell same-named devices apart; "" = any
    char uniq[40];  // e.g. the Bluetooth address; tells identical pads apart; "" = any
};
static struct prio_entry g_prio[MAX_PRIO];
static int g_nprio = 0;
static int g_active = -1;          // index into g_prio of the attached source, or -1
static char g_active_name[NAME_LEN];  // the attached device's real name and id
static char g_active_id[32];
static char g_active_uniq[40];
// Everything the virtual device declares, so we can release it all when switching sources.
static int g_keys[KEY_MAX + 1], g_nkeys = 0;
static int g_axes[ABS_MAX + 1], g_naxes = 0;
static const char *g_virtual_name = DEFAULT_VIRTUAL_NAME;

static int g_src = -1;             // grabbed source device, or -1 if not present
static int g_ui = -1;              // uinput fd; created once, kept for the process lifetime
static int g_decoy = -1;           // decoy gamepad, see create_decoy()
static int g_use_decoy = 1;        // saved option: create the decoy at startup
static int g_dropping = 0;         // discarding events until the next SYN_REPORT after SYN_DROPPED
static int g_synth_triggers = 0;   // source has BTN_TL2/TR2 buttons: synthesize ABS_BRAKE/ABS_GAS from them...
static int g_analog_seen[2];       // ...until the source actually sends that analog axis (0 = BRAKE, 1 = GAS)
static int g_trigger_max = 32767;  // full-press value of the virtual device's ABS_GAS/ABS_BRAKE
// Range of each axis the virtual device declares, so other pads' axes can be scaled onto it.
static long g_dmin[ABS_MAX + 1], g_dmax[ABS_MAX + 1];
// Axis remap for the attached source: its code -> a virtual-device code, with scaling.
// Most Linux pads report the right stick as RX/RY (and analog triggers as Z/RZ), while
// the virtual device follows the Retroid convention: right stick on Z/RZ, triggers on
// BRAKE/GAS.
static struct axis_remap {
    int active;
    int dst;
    long smin, smax;
} g_remap[ABS_MAX + 1];

static void on_signal(int sig) {
    (void)sig;
    g_stop = 1;
}

static void logf_(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void logf_(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "gamepad_merger: ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    fflush(stderr);
    va_end(ap);
}

// ---- latency stats (-t) ---------------------------------------------------

// Samples (microseconds) from the source's kernel timestamp of a SYN_REPORT to
// the moment we finished writing it to uinput: scheduler wake-up + read + write.
#define MAX_SAMPLES 65536
#define STATS_INTERVAL_S 10
static long g_samples[MAX_SAMPLES];
static int g_nsamples = 0;
static time_t g_last_report = 0;

static long now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000L + ts.tv_nsec / 1000;
}

static int cmp_long(const void *a, const void *b) {
    long x = *(const long *)a, y = *(const long *)b;
    return (x > y) - (x < y);
}

static void report_stats(void) {
    if (g_nsamples == 0) return;
    qsort(g_samples, g_nsamples, sizeof(long), cmp_long);
    logf_("latency us over %d frames: min %ld p50 %ld p90 %ld p99 %ld max %ld",
          g_nsamples, g_samples[0], g_samples[g_nsamples / 2],
          g_samples[g_nsamples * 9 / 10], g_samples[g_nsamples * 99 / 100],
          g_samples[g_nsamples - 1]);
    g_nsamples = 0;
}

// ---- device discovery -----------------------------------------------------

static void format_id(const struct input_id *id, char *out, size_t n) {
    snprintf(out, n, "%04x:%04x:%04x:%04x", id->bustype, id->vendor, id->product, id->version);
}

// The Retroid's RsMapping service publishes a re-mapped copy of an external pad
// (2022:3001, non-zero version) and hides the original's /dev/input node while it
// "holds" that pad. Only the onboard controls use version 0000.
static int is_vendor_copy_id(const char *id) {
    return strncmp(id, "0003:2022:3001:", 15) == 0 && strcmp(id + 15, "0000") != 0;
}

// Devices the kernel knows, from /proc/bus/input/devices. Unlike /dev/input this still
// lists a pad whose node the Retroid service has hidden, and it shows the pad's uniq.
struct kdev {
    char name[NAME_LEN];
    char id[32];
    char uniq[40];
    int event;  // N of /dev/input/eventN, or -1
};

static int read_kdevs(struct kdev *out, int max) {
    FILE *f = fopen("/proc/bus/input/devices", "r");
    if (!f) return 0;
    char line[512];
    int n = 0, have = 0;
    struct kdev cur;
    memset(&cur, 0, sizeof(cur));
    cur.event = -1;
    while (fgets(line, sizeof(line), f)) {
        line[strcspn(line, "\r\n")] = 0;
        if (line[0] == 0) {  // blank line ends a device block
            if (have && n < max) out[n++] = cur;
            memset(&cur, 0, sizeof(cur));
            cur.event = -1;
            have = 0;
        } else if (strncmp(line, "I: ", 3) == 0) {
            unsigned b, v, p, r;
            if (sscanf(line, "I: Bus=%x Vendor=%x Product=%x Version=%x", &b, &v, &p, &r) == 4) {
                snprintf(cur.id, sizeof(cur.id), "%04x:%04x:%04x:%04x", b, v, p, r);
                have = 1;
            }
        } else if (strncmp(line, "N: Name=\"", 9) == 0) {
            char *q = line + 9;
            size_t l = strlen(q);
            if (l && q[l - 1] == '"') q[l - 1] = 0;
            snprintf(cur.name, sizeof(cur.name), "%s", q);
        } else if (strncmp(line, "U: Uniq=", 8) == 0) {
            snprintf(cur.uniq, sizeof(cur.uniq), "%s", line + 8);
        } else if (strncmp(line, "H: Handlers=", 12) == 0) {
            const char *e = strstr(line, "event");
            if (e) cur.event = atoi(e + 5);
        }
    }
    if (have && n < max) out[n++] = cur;
    fclose(f);
    return n;
}

// A real (non-copy) device called `name` (with this `uniq`, if given) that the kernel has
// but whose /dev/input node is hidden: the Retroid service is holding it and only its
// copy is usable. Fills `res` and returns 1 if found.
static int find_held_raw(const char *name, const char *uniq, struct kdev *res) {
    struct kdev k[64];
    int n = read_kdevs(k, 64);
    for (int i = 0; i < n; i++) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", k[i].event);
        if (strcmp(k[i].name, name) == 0 && !is_vendor_copy_id(k[i].id) && k[i].event >= 0 &&
            access(path, F_OK) != 0 && (uniq[0] == 0 || strcmp(k[i].uniq, uniq) == 0)) {
            *res = k[i];
            return 1;
        }
    }
    return 0;
}

static void read_uniq(int fd, char *out, size_t n) {
    out[0] = 0;
    if (ioctl(fd, EVIOCGUNIQ(n - 1), out) < 0) out[0] = 0;
    out[n - 1] = 0;
}

// Opens the first /dev/input/event* whose EVIOCGNAME equals `name` (and, if
// `id` is non-empty, whose bus:vendor:product:version matches). If the wanted
// device isn't there but the vendor's copy of a pad with that name is, returns the
// copy instead, so a pad keeps working whether or not the service is holding it.
// With an empty `id`, the real device is preferred over its copy. Returns fd or -1.
static int open_by_name(const char *name, const char *id, const char *uniq) {
    DIR *d = opendir("/dev/input");
    if (!d) {
        logf_("opendir /dev/input: %s", strerror(errno));
        return -1;
    }
    int found = -1, copy = -1;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        char devname[NAME_LEN] = {0};
        struct input_id iid;
        char idstr[32] = "";
        char duniq[40];
        if (ioctl(fd, EVIOCGID, &iid) == 0) format_id(&iid, idstr, sizeof(idstr));
        read_uniq(fd, duniq, sizeof(duniq));
        if (ioctl(fd, EVIOCGNAME(sizeof(devname) - 1), devname) >= 0 && strcmp(devname, name) == 0) {
            int exact = (id[0] == 0 ? !is_vendor_copy_id(idstr) : strcmp(id, idstr) == 0) &&
                        (uniq[0] == 0 || strcmp(uniq, duniq) == 0);
            if (exact) {
                found = fd;
                break;
            }
            // The vendor's copy has no uniq of its own. It stands in for a wanted pad only if
            // that pad really is the one the service is holding.
            struct kdev held;
            if (copy < 0 && is_vendor_copy_id(idstr) && !is_vendor_copy_id(id) &&
                (uniq[0] == 0 || find_held_raw(name, uniq, &held))) {
                copy = fd;  // remember as a fallback; keep looking for the real one
                continue;
            }
        }
        close(fd);
    }
    closedir(d);
    if (found < 0) {
        found = copy;
        copy = -1;
    }
    if (copy >= 0) close(copy);
    return found;
}

// True if the device reports any button in the joystick/gamepad range.
static int looks_like_gamepad(int fd) {
    unsigned long keybits[NLONGS(KEY_MAX + 1)] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) < 0) return 0;
    for (int k = BTN_JOYSTICK; k < BTN_DIGI; k++)
        if (TEST_BIT(k, keybits)) return 1;
    return 0;
}

// ---- virtual device -------------------------------------------------------

// Creates a uinput device declaring the same keys/axes as `src`. Returns the fd or -1.
static int create_virtual_from(int src, const char *vname) {
    g_nkeys = g_naxes = 0;
    int ui = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (ui < 0) {
        logf_("open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    unsigned long evbits[NLONGS(EV_MAX + 1)] = {0};
    if (ioctl(src, EVIOCGBIT(0, sizeof(evbits)), evbits) < 0) {
        logf_("EVIOCGBIT(0): %s", strerror(errno));
        goto fail;
    }

    if (TEST_BIT(EV_KEY, evbits)) {
        unsigned long keybits[NLONGS(KEY_MAX + 1)] = {0};
        if (ioctl(src, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) < 0) {
            logf_("EVIOCGBIT(EV_KEY): %s", strerror(errno));
            goto fail;
        }
        ioctl(ui, UI_SET_EVBIT, EV_KEY);
        for (int k = 0; k <= KEY_MAX; k++)
            if (TEST_BIT(k, keybits)) {
                ioctl(ui, UI_SET_KEYBIT, k);
                g_keys[g_nkeys++] = k;
            }
    }

    if (TEST_BIT(EV_ABS, evbits)) {
        unsigned long absbits[NLONGS(ABS_MAX + 1)] = {0};
        if (ioctl(src, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) < 0) {
            logf_("EVIOCGBIT(EV_ABS): %s", strerror(errno));
            goto fail;
        }
        ioctl(ui, UI_SET_EVBIT, EV_ABS);
        for (int a = 0; a <= ABS_MAX; a++) {
            if (!TEST_BIT(a, absbits)) continue;
            struct input_absinfo info;
            if (ioctl(src, EVIOCGABS(a), &info) < 0) {
                logf_("EVIOCGABS(%d): %s", a, strerror(errno));
                goto fail;
            }
            struct uinput_abs_setup as;
            memset(&as, 0, sizeof(as));
            as.code = a;
            as.absinfo = info;
            if (ioctl(ui, UI_SET_ABSBIT, a) < 0 || ioctl(ui, UI_ABS_SETUP, &as) < 0) {
                logf_("abs setup %d: %s", a, strerror(errno));
                goto fail;
            }
            g_axes[g_naxes++] = a;
            g_dmin[a] = info.minimum;
            g_dmax[a] = info.maximum;
        }
    }

    // The virtual device always has analog triggers (canonical layout, see
    // spec.md), even if this source is digital-only; those get synthesized.
    ioctl(ui, UI_SET_EVBIT, EV_ABS);
    ioctl(ui, UI_SET_EVBIT, EV_KEY);
    for (int i = 0; i < 2; i++) {
        int code = i == 0 ? ABS_GAS : ABS_BRAKE;
        unsigned long have[NLONGS(ABS_MAX + 1)] = {0};
        ioctl(src, EVIOCGBIT(EV_ABS, sizeof(have)), have);
        if (TEST_BIT(code, have)) {
            struct input_absinfo info;
            if (ioctl(src, EVIOCGABS(code), &info) == 0) g_trigger_max = info.maximum;
            continue;  // already declared from the source above
        }
        struct uinput_abs_setup as;
        memset(&as, 0, sizeof(as));
        as.code = code;
        as.absinfo.maximum = g_trigger_max;
        if (ioctl(ui, UI_SET_ABSBIT, code) < 0 || ioctl(ui, UI_ABS_SETUP, &as) < 0) {
            logf_("trigger abs setup %d: %s", code, strerror(errno));
            goto fail;
        }
        g_axes[g_naxes++] = code;
        g_dmin[code] = 0;
        g_dmax[code] = g_trigger_max;
    }

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = VIRTUAL_VENDOR;
    us.id.product = VIRTUAL_PRODUCT;
    us.id.version = VIRTUAL_VERSION;
    snprintf(us.name, UINPUT_MAX_NAME_SIZE, "%s", vname);
    if (ioctl(ui, UI_DEV_SETUP, &us) < 0 || ioctl(ui, UI_DEV_CREATE) < 0) {
        logf_("UI_DEV_SETUP/CREATE: %s", strerror(errno));
        goto fail;
    }
    logf_("created virtual device \"%s\"", vname);
    return ui;

fail:
    close(ui);
    return -1;
}

// Retroid's RsMapping service adopts ONE gamepad-like device at a time: it
// creates its own re-mapped copy under the same name and hides the original's
// /dev/input node, so apps would see the copy instead of our virtual gamepad
// (with vendor button layout, and a different device number every time the set
// of pads changes). It sticks to the first device it adopts until that
// disappears, and adopts whatever gamepad-like device appears first when no
// external pad is present. So we create a silent decoy first; RsMapping adopts
// that and leaves the real virtual gamepad alone. Observed on a Retroid Pocket
// Nova; there is no setting for this that we know of. Never sends any events.
static int create_decoy(void) {
    int ui = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (ui < 0) {
        logf_("decoy: open /dev/uinput: %s", strerror(errno));
        return -1;
    }
    ioctl(ui, UI_SET_EVBIT, EV_KEY);
    for (int k = BTN_SOUTH; k <= BTN_THUMBR; k++) ioctl(ui, UI_SET_KEYBIT, k);
    ioctl(ui, UI_SET_EVBIT, EV_ABS);
    static const int axes[] = {ABS_X, ABS_Y, ABS_Z, ABS_RZ};
    for (size_t i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) {
        struct uinput_abs_setup as;
        memset(&as, 0, sizeof(as));
        as.code = axes[i];
        as.absinfo.minimum = -32767;
        as.absinfo.maximum = 32767;
        ioctl(ui, UI_SET_ABSBIT, axes[i]);
        ioctl(ui, UI_ABS_SETUP, &as);
    }
    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = VIRTUAL_VENDOR;
    us.id.product = VIRTUAL_PRODUCT + 1;
    us.id.version = VIRTUAL_VERSION;
    snprintf(us.name, UINPUT_MAX_NAME_SIZE, "%s", DECOY_NAME);
    if (ioctl(ui, UI_DEV_SETUP, &us) < 0 || ioctl(ui, UI_DEV_CREATE) < 0) {
        logf_("decoy: UI_DEV_SETUP/CREATE: %s", strerror(errno));
        close(ui);
        return -1;
    }
    logf_("created decoy device \"%s\"", DECOY_NAME);
    return ui;
}

static void set_decoy(int on) {
    if (on && g_decoy < 0) {
        g_decoy = create_decoy();
    } else if (!on && g_decoy >= 0) {
        ioctl(g_decoy, UI_DEV_DESTROY);
        close(g_decoy);
        g_decoy = -1;
        logf_("removed decoy device");
    }
}

// ---- priority list --------------------------------------------------------

static void save_config(void) {
    FILE *f = fopen(CONFIG_PATH, "w");
    if (!f) {
        logf_("cannot write %s: %s", CONFIG_PATH, strerror(errno));
        return;
    }
    fprintf(f, "@decoy\t%d\n", g_use_decoy);
    for (int i = 0; i < g_nprio; i++) fprintf(f, "%s\t%s\t%s\n", g_prio[i].name, g_prio[i].id, g_prio[i].uniq);
    fchmod(fileno(f), 0666);  // the daemon may run as root or shell across restarts
    fclose(f);
}

// Returns the number of entries loaded.
static int load_config(void) {
    FILE *f = fopen(CONFIG_PATH, "r");
    if (!f) return 0;
    char line[NAME_LEN + 64];
    g_nprio = 0;
    while (g_nprio < MAX_PRIO && fgets(line, sizeof(line), f)) {
        line[strcspn(line, "\r\n")] = 0;
        if (strncmp(line, "@decoy\t", 7) == 0) {  // saved option, not a priority entry
            g_use_decoy = line[7] != '0';
            continue;
        }
        char *tab = strchr(line, '\t');
        char *id = "", *uniq = "";
        if (tab) {
            *tab = 0;
            id = tab + 1;
            char *tab2 = strchr(id, '\t');
            if (tab2) {
                *tab2 = 0;
                uniq = tab2 + 1;
            }
        }
        if (line[0] == 0) continue;
        snprintf(g_prio[g_nprio].name, NAME_LEN, "%s", line);
        snprintf(g_prio[g_nprio].id, sizeof(g_prio[0].id), "%s", id);
        snprintf(g_prio[g_nprio].uniq, sizeof(g_prio[0].uniq), "%s", uniq);
        g_nprio++;
    }
    fclose(f);
    return g_nprio;
}

// ---- clients --------------------------------------------------------------

struct client {
    int fd;                // -1 = free slot
    char buf[2048];
    int len;
    int sniff_fd;          // separate device being sniffed, or -1
    int sniff_source;      // 1 = mirror events from the grabbed source instead
};
static struct client g_clients[MAX_CLIENTS];

static int send_str(int fd, const char *s) {
    ssize_t r = send(fd, s, strlen(s), MSG_DONTWAIT | MSG_NOSIGNAL);
    return (r < 0 && errno != EAGAIN) ? -1 : 0;
}

static void stop_sniff(struct client *c) {
    if (c->sniff_fd >= 0) close(c->sniff_fd);
    c->sniff_fd = -1;
    c->sniff_source = 0;
}

static void drop_client(struct client *c) {
    stop_sniff(c);
    if (c->fd >= 0) close(c->fd);
    c->fd = -1;
    c->len = 0;
}

static void send_event_line(struct client *c, const struct input_event *ev) {
    if (ev->type != EV_KEY && ev->type != EV_ABS) return;
    char line[64];
    snprintf(line, sizeof(line), "E %u %u %d\n", ev->type, ev->code, ev->value);
    if (send_str(c->fd, line) < 0) drop_client(c);
}

// Splits "a<TAB>b<TAB>c" in place into up to 3 fields; missing ones are "".
static void split3(char *arg, char **a, char **b, char **c) {
    *a = arg;
    *b = *c = arg + strlen(arg);
    char *t1 = strchr(arg, '\t');
    if (!t1) return;
    *t1 = 0;
    *b = t1 + 1;
    char *t2 = strchr(*b, '\t');
    if (!t2) return;
    *t2 = 0;
    *c = t2 + 1;
}

static int entry_matches(const struct prio_entry *e, const char *name, const char *id, const char *uniq) {
    return strcmp(e->name, name) == 0 && (e->id[0] == 0 || strcmp(e->id, id) == 0) &&
           (e->uniq[0] == 0 || strcmp(e->uniq, uniq) == 0);
}

// True if this name/id/uniq is the device we are currently forwarding from.
static int is_source(const char *name, const char *id, const char *uniq) {
    return g_src >= 0 && strcmp(name, g_active_name) == 0 && (id[0] == 0 || strcmp(id, g_active_id) == 0) &&
           (uniq[0] == 0 || strcmp(uniq, g_active_uniq) == 0);
}

static void detach_source(void);
static int check_priority(void);
static void recreate_virtual(void);

// Replaces the priority list with the given (name, id, uniq) triples, saves it, and re-evaluates.
static void set_priority(char **f, int n) {
    g_nprio = n;
    for (int i = 0; i < n; i++) {
        snprintf(g_prio[i].name, NAME_LEN, "%s", f[3 * i]);
        snprintf(g_prio[i].id, sizeof(g_prio[0].id), "%s", f[3 * i + 1]);
        snprintf(g_prio[i].uniq, sizeof(g_prio[0].uniq), "%s", f[3 * i + 2]);
    }
    save_config();
    if (g_src >= 0) {
        int found = -1;  // is the attached device still in the list, and where?
        for (int i = 0; i < g_nprio; i++)
            if (entry_matches(&g_prio[i], g_active_name, g_active_id, g_active_uniq)) {
                found = i;
                break;
            }
        if (found < 0) detach_source();
        else g_active = found;
    }
    check_priority();
}

static void handle_line(struct client *c, char *line) {
    if (strcmp(line, "PING") == 0) {
        send_str(c->fd, "PONG\n");
    } else if (strcmp(line, "STATUS") == 0) {
        char out[NAME_LEN * 2 + 32];
        const char *name = g_src >= 0 ? g_active_name : (g_nprio > 0 ? g_prio[0].name : "");
        const char *id = g_src >= 0 ? g_active_id : (g_nprio > 0 ? g_prio[0].id : "");
        const char *uniq = g_src >= 0 ? g_active_uniq : (g_nprio > 0 ? g_prio[0].uniq : "");
        snprintf(out, sizeof(out), "S\t%s\t%d\t%s\t%s\t%d\t%s\n", name, g_src >= 0, g_virtual_name, id,
                 g_decoy >= 0, uniq);
        send_str(c->fd, out);
    } else if (strcmp(line, "LIST") == 0) {
        DIR *d = opendir("/dev/input");
        struct dirent *e;
        while (d && (e = readdir(d)) != NULL) {
            if (strncmp(e->d_name, "event", 5) != 0) continue;
            char path[64];
            snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
            int fd = open(path, O_RDONLY | O_CLOEXEC);
            if (fd < 0) continue;
            char name[NAME_LEN] = {0};
            struct input_id id;
            if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 &&
                ioctl(fd, EVIOCGID, &id) >= 0 && strcmp(name, g_virtual_name) != 0 &&
                strcmp(name, DECOY_NAME) != 0 &&
                looks_like_gamepad(fd)) {
                char out[NAME_LEN + 128];
                char idstr[32], duniq[40];
                format_id(&id, idstr, sizeof(idstr));
                read_uniq(fd, duniq, sizeof(duniq));
                // The vendor's copy of a held pad is listed as the pad itself (its real ID and
                // uniq), so a pad looks the same to the app whether or not it is being held.
                struct kdev held;
                if (is_vendor_copy_id(idstr) && find_held_raw(name, "", &held)) {
                    snprintf(idstr, sizeof(idstr), "%s", held.id);
                    snprintf(duniq, sizeof(duniq), "%s", held.uniq);
                }
                snprintf(out, sizeof(out), "D\t%s\t%s\t%d\t%s\n", name, idstr, is_source(name, idstr, duniq), duniq);
                send_str(c->fd, out);
            }
            close(fd);
        }
        if (d) closedir(d);
        send_str(c->fd, "END\n");
    } else if (strncmp(line, "SOURCE ", 7) == 0) {
        char *one[3];
        split3(line + 7, &one[0], &one[1], &one[2]);
        logf_("source set to \"%s\" [%s] [%s]", one[0], one[1], one[2]);
        set_priority(one, 1);
        send_str(c->fd, "OK\n");
    } else if (strncmp(line, "PRIORITY", 8) == 0 && (line[8] == 0 || line[8] == '\t')) {
        char *f[3 * MAX_PRIO];
        int nf = 0;
        char *p = line + 8;
        while (*p == '\t' && nf < 3 * MAX_PRIO) {
            *p++ = 0;
            f[nf++] = p;
            while (*p && *p != '\t') p++;
        }
        if (nf % 3 != 0 || *p != 0) {
            send_str(c->fd, "ERR badlist\n");
        } else {
            logf_("priority list set (%d entries)", nf / 3);
            set_priority(f, nf / 3);
            send_str(c->fd, "OK\n");
        }
    } else if (strcmp(line, "GETPRIO") == 0) {
        for (int i = 0; i < g_nprio; i++) {
            int fd = open_by_name(g_prio[i].name, g_prio[i].id, g_prio[i].uniq);
            char out[NAME_LEN + 128];
            snprintf(out, sizeof(out), "P\t%s\t%s\t%s\t%d\t%d\n", g_prio[i].name, g_prio[i].id, g_prio[i].uniq,
                     fd >= 0 || i == g_active, i == g_active && g_src >= 0);
            if (fd >= 0) close(fd);
            send_str(c->fd, out);
        }
        send_str(c->fd, "END\n");
    } else if (strncmp(line, "SNIFF ", 6) == 0) {
        stop_sniff(c);
        char *name, *id, *uniq;
        split3(line + 6, &name, &id, &uniq);
        if (is_source(name, id, uniq)) {
            c->sniff_source = 1;
            send_str(c->fd, "OK\n");
        } else if ((c->sniff_fd = open_by_name(name, id, uniq)) >= 0) {
            send_str(c->fd, "OK\n");
        } else {
            send_str(c->fd, "ERR notfound\n");
        }
    } else if (strncmp(line, "DECOY ", 6) == 0 && (line[6] == '0' || line[6] == '1') && line[7] == 0) {
        g_use_decoy = line[6] == '1';
        set_decoy(g_use_decoy);
        save_config();
        send_str(c->fd, "OK\n");
    } else if (strcmp(line, "RECREATE") == 0) {
        recreate_virtual();
        send_str(c->fd, "OK\n");
    } else if (strcmp(line, "STOP") == 0) {
        stop_sniff(c);
        send_str(c->fd, "OK\n");
    } else {
        send_str(c->fd, "ERR unknown\n");
    }
}

static void read_client(struct client *c) {
    ssize_t n = recv(c->fd, c->buf + c->len, sizeof(c->buf) - c->len - 1, MSG_DONTWAIT);
    if (n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) {
        drop_client(c);
        return;
    }
    if (n < 0) return;
    c->len += n;
    c->buf[c->len] = 0;
    char *start = c->buf, *nl;
    while (c->fd >= 0 && (nl = strchr(start, '\n')) != NULL) {
        *nl = 0;
        if (nl > start && nl[-1] == '\r') nl[-1] = 0;
        handle_line(c, start);
        start = nl + 1;
    }
    if (c->fd < 0) return;
    c->len = (int)(c->buf + c->len - start);
    memmove(c->buf, start, c->len);
    if (c->len >= (int)sizeof(c->buf) - 1) drop_client(c);  // line too long
}

static void accept_client(int lfd) {
    int fd = accept4(lfd, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
    if (fd < 0) return;
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0 ||
        (g_allowed_uid >= 0 && cred.uid != 0 && cred.uid != UID_SHELL &&
         cred.uid != (uid_t)g_allowed_uid)) {
        logf_("rejected client uid %d", len == sizeof(cred) ? (int)cred.uid : -1);
        close(fd);
        return;
    }
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (g_clients[i].fd < 0) {
            g_clients[i].fd = fd;
            g_clients[i].len = 0;
            g_clients[i].sniff_fd = -1;
            g_clients[i].sniff_source = 0;
            return;
        }
    }
    close(fd);  // full
}

static int listen_abstract(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path + 1, SOCKET_NAME, strlen(SOCKET_NAME));  // sun_path[0] = 0: abstract
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCKET_NAME);
    if (bind(fd, (struct sockaddr *)&addr, len) < 0 || listen(fd, 4) < 0) {
        int e = errno;
        close(fd);
        errno = e;
        return -1;
    }
    return fd;
}

// ---- forwarding -----------------------------------------------------------

static void add_remap(int src_fd, int from, int to) {
    struct input_absinfo info;
    if (ioctl(src_fd, EVIOCGABS(from), &info) < 0 || info.maximum == info.minimum) return;
    g_remap[from].active = 1;
    g_remap[from].dst = to;
    g_remap[from].smin = info.minimum;
    g_remap[from].smax = info.maximum;
}

// Releases every button/axis on the virtual device so nothing stays stuck
// when the source changes or disappears mid-press.
static void reset_virtual(void) {
    if (g_ui < 0) return;
    struct input_event ev;
    for (int i = 0; i < g_nkeys + g_naxes + 1; i++) {
        memset(&ev, 0, sizeof(ev));
        if (i < g_nkeys) { ev.type = EV_KEY; ev.code = g_keys[i]; }
        else if (i < g_nkeys + g_naxes) { ev.type = EV_ABS; ev.code = g_axes[i - g_nkeys]; }
        else { ev.type = EV_SYN; ev.code = SYN_REPORT; }
        if (write(g_ui, &ev, sizeof(ev)) < 0 && errno != EAGAIN) break;
    }
}

static void detach_source(void) {
    if (g_src >= 0) {
        ioctl(g_src, EVIOCGRAB, 0);
        close(g_src);
        g_src = -1;
        reset_virtual();
        // Clients tapping the forwarded stream are now watching the wrong device.
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i].fd >= 0 && g_clients[i].sniff_source) {
                stop_sniff(&g_clients[i]);
                if (send_str(g_clients[i].fd, "GONE\n") < 0) drop_client(&g_clients[i]);
            }
        }
    }
    g_active = -1;
    g_dropping = 0;
}

// Opens, configures and grabs priority entry `idx`. Creates the virtual device
// on first success. Returns -1 only on a fatal (virtual device) error.
static int attach_entry(int idx) {
    int src = open_by_name(g_prio[idx].name, g_prio[idx].id, g_prio[idx].uniq);
    if (src < 0) return 0;  // not present; caller retries later
    snprintf(g_active_name, sizeof(g_active_name), "%s", g_prio[idx].name);
    g_active_id[0] = 0;
    struct input_id iid;
    if (ioctl(src, EVIOCGID, &iid) == 0) format_id(&iid, g_active_id, sizeof(g_active_id));
    read_uniq(src, g_active_uniq, sizeof(g_active_uniq));
    // If we attached the vendor's copy of a held pad, record the pad's own identity.
    struct kdev held;
    if (is_vendor_copy_id(g_active_id) && find_held_raw(g_active_name, g_prio[idx].uniq, &held)) {
        snprintf(g_active_id, sizeof(g_active_id), "%s", held.id);
        snprintf(g_active_uniq, sizeof(g_active_uniq), "%s", held.uniq);
    }
    logf_("source #%d: \"%s\" [%s] [%s]", idx, g_active_name, g_active_id, g_active_uniq);
    if (g_ui < 0) {
        g_ui = create_virtual_from(src, g_virtual_name);
        if (g_ui < 0) {
            close(src);
            return -1;
        }
    }
    if (g_stats) {
        // Make the source's event timestamps comparable with now_us().
        int clk = CLOCK_MONOTONIC;
        if (ioctl(src, EVIOCSCLOCKID, &clk) < 0)
            logf_("EVIOCSCLOCKID failed (%s); latency numbers invalid", strerror(errno));
    }
    // Grab so apps see only the virtual device, not the physical one too.
    if (ioctl(src, EVIOCGRAB, 1) < 0)
        logf_("EVIOCGRAB failed (%s); apps will see both devices", strerror(errno));
    unsigned long absbits[NLONGS(ABS_MAX + 1)] = {0};
    unsigned long keybits[NLONGS(KEY_MAX + 1)] = {0};
    ioctl(src, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    ioctl(src, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
    // Declared axes don't prove the source drives them (the Retroid's virtual
    // "Nintendo Switch Pro Controller" declares GAS/BRAKE but only sends the
    // digital buttons), so synthesize until real analog events show up.
    (void)absbits;
    g_synth_triggers = TEST_BIT(BTN_TL2, keybits) || TEST_BIT(BTN_TR2, keybits);
    g_analog_seen[0] = g_analog_seen[1] = 0;
    memset(g_remap, 0, sizeof(g_remap));
    if (TEST_BIT(ABS_RX, absbits) && TEST_BIT(ABS_RY, absbits)) {
        // Standard Linux layout: right stick on RX/RY -> our Z/RZ.
        add_remap(src, ABS_RX, ABS_Z);
        add_remap(src, ABS_RY, ABS_RZ);
        // Xbox/PlayStation-style pads put the analog triggers on Z/RZ.
        if (TEST_BIT(ABS_Z, absbits) && TEST_BIT(ABS_RZ, absbits)) {
            add_remap(src, ABS_Z, ABS_BRAKE);
            add_remap(src, ABS_RZ, ABS_GAS);
        }
        logf_("remapping right stick RX/RY -> Z/RZ%s", g_remap[ABS_Z].active ? ", triggers Z/RZ -> BRAKE/GAS" : "");
    }
    g_src = src;
    g_active = idx;
    g_dropping = 0;
    reset_virtual();
    return 0;
}

// Attaches the best present source. Only entries ranked above the current one
// matter (when already attached): if one of them has appeared, switch to it.
// Returns -1 only on a fatal error.
static int check_priority(void) {
    int limit = g_src >= 0 ? g_active : g_nprio;
    for (int i = 0; i < limit; i++) {
        int fd = open_by_name(g_prio[i].name, g_prio[i].id, g_prio[i].uniq);
        if (fd < 0) continue;
        close(fd);
        detach_source();
        return attach_entry(i);
    }
    return 0;
}

// Destroys and re-creates the virtual gamepad, re-attaching the current source. The
// Retroid service checks its ignore list only when a device appears, so this is how
// a newly added ignore entry takes effect on a device it already holds.
static void recreate_virtual(void) {
    if (g_ui < 0) return;
    detach_source();
    ioctl(g_ui, UI_DEV_DESTROY);
    close(g_ui);
    g_ui = -1;
    logf_("recreating virtual device");
    usleep(400 * 1000);  // let other services see it go before it reappears
    check_priority();
}

// Reads a batch from the source and forwards it. Returns -1 on a fatal
// virtual-device write error; a vanished source just detaches.
static int pump_source(void) {
    struct input_event evs[64];
    ssize_t n = read(g_src, evs, sizeof(evs));
    if (n < 0) {
        if (errno == EINTR || errno == EAGAIN) return 0;
        logf_("source \"%s\" gone (read: %s)", g_active_name, strerror(errno));  // ENODEV when unplugged
        detach_source();
        return 0;
    }
    for (size_t i = 0; i < (size_t)n / sizeof(evs[0]); i++) {
        struct input_event *ev = &evs[i];
        if (ev->type == EV_SYN && ev->code == SYN_DROPPED) {
            g_dropping = 1;
            continue;
        }
        if (g_dropping) {
            if (ev->type == EV_SYN && ev->code == SYN_REPORT) g_dropping = 0;
            continue;
        }
        // uinput stamps its own time; pass type/code/value only.
        struct input_event out;
        memset(&out, 0, sizeof(out));
        out.type = ev->type;
        out.code = ev->code;
        out.value = ev->value;
        if (ev->type == EV_ABS && ev->code <= ABS_MAX && g_remap[ev->code].active) {
            const struct axis_remap *m = &g_remap[ev->code];
            out.code = m->dst;
            out.value = (int)(g_dmin[m->dst] + ((long)ev->value - m->smin) * (g_dmax[m->dst] - g_dmin[m->dst]) /
                                                   (m->smax - m->smin));
        }
        if (write(g_ui, &out, sizeof(out)) < 0) {
            if (errno == EAGAIN || errno == EINTR) continue;
            logf_("write uinput: %s", strerror(errno));
            return -1;
        }
        if (out.type == EV_ABS && out.code == ABS_BRAKE) g_analog_seen[0] = 1;
        if (out.type == EV_ABS && out.code == ABS_GAS) g_analog_seen[1] = 1;
        if (g_synth_triggers && ev->type == EV_KEY && (ev->code == BTN_TL2 || ev->code == BTN_TR2) &&
            !g_analog_seen[ev->code == BTN_TL2 ? 0 : 1]) {
            // Android convention: left trigger = ABS_BRAKE, right = ABS_GAS. Instant 0/max, no ramping.
            struct input_event ax;
            memset(&ax, 0, sizeof(ax));
            ax.type = EV_ABS;
            ax.code = ev->code == BTN_TL2 ? ABS_BRAKE : ABS_GAS;
            ax.value = ev->value ? g_trigger_max : 0;
            if (write(g_ui, &ax, sizeof(ax)) < 0 && errno != EAGAIN && errno != EINTR) {
                logf_("write uinput: %s", strerror(errno));
                return -1;
            }
        }
        if (g_stats && ev->type == EV_SYN && ev->code == SYN_REPORT) {
            long t_src = ev->time.tv_sec * 1000000L + ev->time.tv_usec;
            if (g_nsamples < MAX_SAMPLES) g_samples[g_nsamples++] = now_us() - t_src;
            time_t t = time(NULL);
            if (t - g_last_report >= STATS_INTERVAL_S) {
                g_last_report = t;
                report_stats();
            }
        }
        for (int c = 0; c < MAX_CLIENTS; c++)
            if (g_clients[c].fd >= 0 && g_clients[c].sniff_source) send_event_line(&g_clients[c], ev);
    }
    return 0;
}

// A sniffed (non-source) device became readable: stream its key/abs events.
static void pump_sniff(struct client *c) {
    struct input_event evs[64];
    ssize_t n = read(c->sniff_fd, evs, sizeof(evs));
    if (n < 0) {
        if (errno == EINTR || errno == EAGAIN) return;
        stop_sniff(c);
        if (send_str(c->fd, "GONE\n") < 0) drop_client(c);
        return;
    }
    for (size_t i = 0; i < (size_t)n / sizeof(evs[0]) && c->fd >= 0; i++)
        send_event_line(c, &evs[i]);
}

int main(int argc, char **argv) {
    int cli_source = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-t") == 0) g_stats = 1;
        else if (i + 1 < argc && strcmp(argv[i], "-u") == 0) g_allowed_uid = atoi(argv[++i]);
        else if (i + 1 < argc && strcmp(argv[i], "-s") == 0) {
            snprintf(g_prio[0].name, NAME_LEN, "%s", argv[++i]);
            g_nprio = 1;
            cli_source = 1;
        }
        else if (i + 1 < argc && strcmp(argv[i], "-n") == 0) g_virtual_name = argv[++i];
        else {
            fprintf(stderr, "usage: %s [-t] [-u app_uid] [-s source name] [-n virtual name]\n", argv[0]);
            return 2;
        }
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;  // no SA_RESTART: let poll return EINTR
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    for (int i = 0; i < MAX_CLIENTS; i++) g_clients[i].fd = g_clients[i].sniff_fd = -1;

    // Priority list: -s wins, else the saved list, else the Retroid's own controls.
    if (!cli_source && load_config() > 0) logf_("loaded %d priority entries from %s", g_nprio, CONFIG_PATH);
    if (g_nprio == 0) {
        snprintf(g_prio[0].name, NAME_LEN, "%s", DEFAULT_SOURCE_NAME);
        g_nprio = 1;
    }

    // Listen first: if the socket name is taken, another daemon is already running.
    int lfd = listen_abstract();
    if (lfd < 0) {
        logf_("cannot listen on @%s: %s (already running?)", SOCKET_NAME, strerror(errno));
        return 1;
    }

    // Decoy first (unless turned off), and give RsMapping a moment to adopt it, before our
    // real device exists. Failure is not fatal; we just lose the workaround.
    if (g_use_decoy) g_decoy = create_decoy();
    if (g_decoy >= 0) usleep(1500 * 1000);

    long last_check = 0;
    while (!g_stop) {
        // Only needed while something above the active source could still appear.
        if (g_active != 0 && now_us() - last_check >= RESCAN_INTERVAL_MS * 1000L) {
            last_check = now_us();
            if (check_priority() < 0) break;
        }

        // pfds: [0] listen, [1] source, then per client: client fd, sniff fd.
        struct pollfd pfds[2 + MAX_CLIENTS * 2];
        int owner[2 + MAX_CLIENTS * 2];  // client index, or -1
        int kind[2 + MAX_CLIENTS * 2];   // 0 listen, 1 source, 2 client, 3 sniff
        int np = 0;
        pfds[np] = (struct pollfd){lfd, POLLIN, 0}; owner[np] = -1; kind[np++] = 0;
        if (g_src >= 0) { pfds[np] = (struct pollfd){g_src, POLLIN, 0}; owner[np] = -1; kind[np++] = 1; }
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i].fd < 0) continue;
            pfds[np] = (struct pollfd){g_clients[i].fd, POLLIN, 0}; owner[np] = i; kind[np++] = 2;
            if (g_clients[i].sniff_fd >= 0) {
                pfds[np] = (struct pollfd){g_clients[i].sniff_fd, POLLIN, 0}; owner[np] = i; kind[np++] = 3;
            }
        }

        int r = poll(pfds, np, g_active != 0 ? RESCAN_INTERVAL_MS : 500);
        if (r < 0) {
            if (errno == EINTR) continue;
            logf_("poll: %s", strerror(errno));
            break;
        }
        for (int p = 0; p < np && r > 0; p++) {
            if (!pfds[p].revents) continue;
            struct client *c = owner[p] >= 0 ? &g_clients[owner[p]] : NULL;
            switch (kind[p]) {
                case 0: accept_client(lfd); break;
                case 1:
                    // The client handlers below may have detached the source this round.
                    if (g_src != pfds[p].fd) break;
                    if (pfds[p].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                        logf_("source \"%s\" gone (poll revents 0x%x)", g_active_name, pfds[p].revents);
                        detach_source();
                    } else if (pump_source() < 0) {
                        g_stop = 1;
                    }
                    break;
                case 2:
                    if (c->fd == pfds[p].fd) read_client(c);
                    break;
                case 3:
                    if (c->fd >= 0 && c->sniff_fd == pfds[p].fd) pump_sniff(c);
                    break;
            }
        }
    }

    for (int i = 0; i < MAX_CLIENTS; i++) drop_client(&g_clients[i]);
    detach_source();
    if (g_ui >= 0) {
        ioctl(g_ui, UI_DEV_DESTROY);
        close(g_ui);
    }
    if (g_decoy >= 0) {
        ioctl(g_decoy, UI_DEV_DESTROY);
        close(g_decoy);
    }
    close(lfd);
    logf_("exiting");
    return 0;
}
