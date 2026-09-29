// gamepad_merger — CouchMode's root/shell daemon.
//
// Finds one physical evdev node by NAME, mirrors its capabilities into a
// persistent uinput virtual gamepad, grabs the source so apps only see the
// virtual device, and forwards events 1:1. Multi-source priority comes later;
// the source is kept in one place (g_source_name / g_src) so it can grow into
// a list.
//
// The app controls the daemon over an abstract unix socket ("@couchmode"),
// line-based text protocol, one request per line:
//
//   PING                  -> PONG
//   STATUS                -> S<TAB>source<TAB>connected(0/1)<TAB>virtual<TAB>sourceId
//   LIST                  -> D<TAB>name<TAB>bus:vendor:product:version<TAB>isSource(0/1)
//                            ... then END       (gamepad-looking devices only)
//   SOURCE <name>[<TAB>id] -> OK    switch the forwarded source device. The optional id
//                            ("bus:vendor:product:version") disambiguates devices
//                            that share a name (e.g. a pad and the vendor's virtual copy).
//   SNIFF <name>[<TAB>id] -> OK|ERR ...  then "E type code value" lines for
//                            every EV_KEY/EV_ABS event; "GONE" if the device
//                            disappears. Works on the current source too (a
//                            grabbed device can't be opened twice, so we tap
//                            our own forwarding for it).
//   STOP                  -> OK     stop sniffing
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
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define DEFAULT_SOURCE_NAME "Retroid Pocket Controller"
#define DEFAULT_VIRTUAL_NAME "CouchMode Virtual Gamepad"
#define SOCKET_NAME "couchmode"
// Stable identity for the virtual device (pid.codes test VID, arbitrary PID).
#define VIRTUAL_VENDOR 0x1209
#define VIRTUAL_PRODUCT 0xC0DE
#define VIRTUAL_VERSION 1
#define RESCAN_INTERVAL_MS 1000
#define MAX_CLIENTS 4
#define NAME_LEN 256
#define UID_SHELL 2000

#define BITS_PER_LONG (sizeof(unsigned long) * 8)
#define NLONGS(x) (((x) + BITS_PER_LONG - 1) / BITS_PER_LONG)
#define TEST_BIT(bit, arr) ((arr)[(bit) / BITS_PER_LONG] & (1UL << ((bit) % BITS_PER_LONG)))

static volatile sig_atomic_t g_stop = 0;
static int g_stats = 0;            // -t
static int g_allowed_uid = -1;     // -u; -1 = accept any client
static char g_source_name[NAME_LEN] = DEFAULT_SOURCE_NAME;
static char g_source_id[32] = "";  // optional "bus:vendor:product:version" (hex) to tell same-named devices apart
static const char *g_virtual_name = DEFAULT_VIRTUAL_NAME;

static int g_src = -1;             // grabbed source device, or -1 if not present
static int g_ui = -1;              // uinput fd; created once, kept for the process lifetime
static int g_dropping = 0;         // discarding events until the next SYN_REPORT after SYN_DROPPED
static int g_synth_triggers = 0;   // source has BTN_TL2/TR2 buttons: synthesize ABS_BRAKE/ABS_GAS from them...
static int g_analog_seen[2];       // ...until the source actually sends that analog axis (0 = BRAKE, 1 = GAS)
static int g_trigger_max = 32767;  // full-press value of the virtual device's ABS_GAS/ABS_BRAKE

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

// Opens the first /dev/input/event* whose EVIOCGNAME equals `name` (and, if
// `id` is non-empty, whose bus:vendor:product:version matches). Returns fd or -1.
static int open_by_name(const char *name, const char *id) {
    DIR *d = opendir("/dev/input");
    if (!d) {
        logf_("opendir /dev/input: %s", strerror(errno));
        return -1;
    }
    int found = -1;
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
        if (ioctl(fd, EVIOCGID, &iid) == 0) format_id(&iid, idstr, sizeof(idstr));
        if (ioctl(fd, EVIOCGNAME(sizeof(devname) - 1), devname) >= 0 && strcmp(devname, name) == 0 &&
            (id[0] == 0 || strcmp(id, idstr) == 0)) {
            found = fd;
            break;
        }
        close(fd);
    }
    closedir(d);
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
            if (TEST_BIT(k, keybits)) ioctl(ui, UI_SET_KEYBIT, k);
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

// ---- clients --------------------------------------------------------------

struct client {
    int fd;                // -1 = free slot
    char buf[512];
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

// Splits "name<TAB>id" in place; returns the id ("" if absent).
static char *split_id(char *arg) {
    char *tab = strchr(arg, '\t');
    if (!tab) return arg + strlen(arg);
    *tab = 0;
    return tab + 1;
}

static int is_source(const char *name, const char *id) {
    return strcmp(name, g_source_name) == 0 && (g_source_id[0] == 0 || strcmp(id, g_source_id) == 0);
}

static void handle_line(struct client *c, char *line) {
    if (strcmp(line, "PING") == 0) {
        send_str(c->fd, "PONG\n");
    } else if (strcmp(line, "STATUS") == 0) {
        char out[NAME_LEN * 2 + 32];
        snprintf(out, sizeof(out), "S\t%s\t%d\t%s\t%s\n", g_source_name, g_src >= 0, g_virtual_name,
                 g_source_id);
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
                looks_like_gamepad(fd)) {
                char out[NAME_LEN + 64];
                char idstr[32];
                format_id(&id, idstr, sizeof(idstr));
                snprintf(out, sizeof(out), "D\t%s\t%s\t%d\n", name, idstr, is_source(name, idstr));
                send_str(c->fd, out);
            }
            close(fd);
        }
        if (d) closedir(d);
        send_str(c->fd, "END\n");
    } else if (strncmp(line, "SOURCE ", 7) == 0) {
        char *id = split_id(line + 7);
        snprintf(g_source_name, sizeof(g_source_name), "%s", line + 7);
        snprintf(g_source_id, sizeof(g_source_id), "%s", id);
        logf_("source set to \"%s\" [%s]", g_source_name, g_source_id);
        if (g_src >= 0) {  // detach now; the main loop reattaches by the new name
            ioctl(g_src, EVIOCGRAB, 0);
            close(g_src);
            g_src = -1;
        }
        send_str(c->fd, "OK\n");
    } else if (strncmp(line, "SNIFF ", 6) == 0) {
        stop_sniff(c);
        const char *name = line + 6;
        const char *id = split_id(line + 6);
        if (is_source(name, id) || (strcmp(name, g_source_name) == 0 && id[0] == 0)) {
            c->sniff_source = 1;
            send_str(c->fd, "OK\n");
        } else if ((c->sniff_fd = open_by_name(name, id)) >= 0) {
            send_str(c->fd, "OK\n");
        } else {
            send_str(c->fd, "ERR notfound\n");
        }
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

static void detach_source(void) {
    if (g_src >= 0) {
        ioctl(g_src, EVIOCGRAB, 0);
        close(g_src);
        g_src = -1;
    }
    g_dropping = 0;
}

// Opens, configures and grabs the source device. Creates the virtual device on
// first success. Returns -1 only on a fatal (virtual device) error.
static int attach_source(void) {
    int src = open_by_name(g_source_name, g_source_id);
    if (src < 0) return 0;  // not present; caller retries later
    logf_("found source \"%s\"", g_source_name);
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
    g_src = src;
    g_dropping = 0;
    return 0;
}

// Reads a batch from the source and forwards it. Returns -1 on a fatal
// virtual-device write error; a vanished source just detaches.
static int pump_source(void) {
    struct input_event evs[64];
    ssize_t n = read(g_src, evs, sizeof(evs));
    if (n < 0) {
        if (errno == EINTR || errno == EAGAIN) return 0;
        logf_("source gone (read: %s)", strerror(errno));  // ENODEV when unplugged
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
        if (write(g_ui, &out, sizeof(out)) < 0) {
            if (errno == EAGAIN || errno == EINTR) continue;
            logf_("write uinput: %s", strerror(errno));
            return -1;
        }
        if (ev->type == EV_ABS && ev->code == ABS_BRAKE) g_analog_seen[0] = 1;
        if (ev->type == EV_ABS && ev->code == ABS_GAS) g_analog_seen[1] = 1;
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
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-t") == 0) g_stats = 1;
        else if (i + 1 < argc && strcmp(argv[i], "-u") == 0) g_allowed_uid = atoi(argv[++i]);
        else if (i + 1 < argc && strcmp(argv[i], "-s") == 0)
            snprintf(g_source_name, sizeof(g_source_name), "%s", argv[++i]);
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

    // Listen first: if the socket name is taken, another daemon is already running.
    int lfd = listen_abstract();
    if (lfd < 0) {
        logf_("cannot listen on @%s: %s (already running?)", SOCKET_NAME, strerror(errno));
        return 1;
    }

    long last_attach_try = 0;
    while (!g_stop) {
        if (g_src < 0 && now_us() - last_attach_try >= RESCAN_INTERVAL_MS * 1000L) {
            last_attach_try = now_us();
            if (attach_source() < 0) break;
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

        int r = poll(pfds, np, g_src < 0 ? RESCAN_INTERVAL_MS : 500);
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
                        logf_("source gone (poll revents 0x%x)", pfds[p].revents);
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
    close(lfd);
    logf_("exiting");
    return 0;
}
