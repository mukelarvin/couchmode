// gamepad_merger — CouchMode's root/shell daemon.
//
// First cut (Phase 5, single hardcoded source): find one physical evdev node
// by NAME, mirror its capabilities into a persistent uinput virtual gamepad,
// grab the source so apps only see the virtual device, and forward events 1:1.
// Multi-source priority comes later; the source handling is kept in one small
// struct so it can grow into a list.
//
// Usage: gamepad_merger [-s "source device name"] [-n "virtual device name"]
// Default source: "Retroid Pocket Controller".
//
// Match by NAME, never by event number (changes per boot) and never by
// VID:PID alone (the "Retroid Pocket Virtual Mouse" shares 2022:3001).

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define DEFAULT_SOURCE_NAME "Retroid Pocket Controller"
#define DEFAULT_VIRTUAL_NAME "CouchMode Virtual Gamepad"
// Stable identity for the virtual device (pid.codes test VID, arbitrary PID).
#define VIRTUAL_VENDOR 0x1209
#define VIRTUAL_PRODUCT 0xC0DE
#define VIRTUAL_VERSION 1
#define RESCAN_INTERVAL_MS 1000

#define BITS_PER_LONG (sizeof(unsigned long) * 8)
#define NLONGS(x) (((x) + BITS_PER_LONG - 1) / BITS_PER_LONG)
#define TEST_BIT(bit, arr) ((arr)[(bit) / BITS_PER_LONG] & (1UL << ((bit) % BITS_PER_LONG)))

static volatile sig_atomic_t g_stop = 0;
static int g_stats = 0;  // -t: measure and log our added latency

// Latency samples (microseconds) from the source's kernel timestamp of a
// SYN_REPORT to the moment we finished writing it to uinput. That is exactly
// what this daemon adds: scheduler wake-up + read + write.
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

static void report_stats(void) {
    if (g_nsamples == 0) return;
    qsort(g_samples, g_nsamples, sizeof(long), cmp_long);
    logf_("latency us over %d frames: min %ld p50 %ld p90 %ld p99 %ld max %ld",
          g_nsamples, g_samples[0], g_samples[g_nsamples / 2],
          g_samples[g_nsamples * 9 / 10], g_samples[g_nsamples * 99 / 100],
          g_samples[g_nsamples - 1]);
    g_nsamples = 0;
}

// ---- source discovery -----------------------------------------------------

// Opens the first /dev/input/event* whose EVIOCGNAME equals `name`.
// Returns fd or -1.
static int open_source_by_name(const char *name) {
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
        char devname[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(devname) - 1), devname) >= 0 &&
            strcmp(devname, name) == 0) {
            logf_("found source \"%s\" at %s", name, path);
            found = fd;
            break;
        }
        close(fd);
    }
    closedir(d);
    return found;
}

// ---- virtual device -------------------------------------------------------

// Creates a uinput device declaring the same keys/axes as `src`.
// Returns the uinput fd or -1.
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

// ---- forwarding -----------------------------------------------------------

// Forwards events from src to ui until the source goes away or we're told to
// stop. Returns 0 if the source vanished (caller should rescan), -1 on a fatal
// virtual-device error.
static int forward(int src, int ui) {
    int dropping = 0;  // discarding events until the next SYN_REPORT after SYN_DROPPED
    struct pollfd pfd = {.fd = src, .events = POLLIN};

    while (!g_stop) {
        int r = poll(&pfd, 1, 500);
        if (r < 0) {
            if (errno == EINTR) continue;
            logf_("poll: %s", strerror(errno));
            return 0;
        }
        if (r == 0) continue;
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            logf_("source gone (poll revents 0x%x)", pfd.revents);
            return 0;
        }

        struct input_event evs[64];
        ssize_t n = read(src, evs, sizeof(evs));
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            logf_("read source: %s", strerror(errno));  // ENODEV when unplugged
            return 0;
        }
        for (size_t i = 0; i < (size_t)n / sizeof(evs[0]); i++) {
            struct input_event *ev = &evs[i];
            if (ev->type == EV_SYN && ev->code == SYN_DROPPED) {
                dropping = 1;
                continue;
            }
            if (dropping) {
                if (ev->type == EV_SYN && ev->code == SYN_REPORT) dropping = 0;
                continue;
            }
            // uinput stamps its own time; pass type/code/value only.
            struct input_event out;
            memset(&out, 0, sizeof(out));
            out.type = ev->type;
            out.code = ev->code;
            out.value = ev->value;
            if (write(ui, &out, sizeof(out)) < 0) {
                if (errno == EAGAIN || errno == EINTR) continue;
                logf_("write uinput: %s", strerror(errno));
                return -1;
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
        }
    }
    return 0;
}

static void sleep_ms(int ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

int main(int argc, char **argv) {
    const char *source_name = DEFAULT_SOURCE_NAME;
    const char *virtual_name = DEFAULT_VIRTUAL_NAME;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-t") == 0) g_stats = 1;
        else if (i + 1 < argc && strcmp(argv[i], "-s") == 0) source_name = argv[++i];
        else if (i + 1 < argc && strcmp(argv[i], "-n") == 0) virtual_name = argv[++i];
        else {
            fprintf(stderr, "usage: %s [-t] [-s source name] [-n virtual name]\n", argv[0]);
            return 2;
        }
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;  // no SA_RESTART: let poll/read return EINTR
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    int ui = -1;  // created once, from the first source, then kept for the process lifetime
    while (!g_stop) {
        int src = open_source_by_name(source_name);
        if (src < 0) {
            sleep_ms(RESCAN_INTERVAL_MS);
            continue;
        }
        if (ui < 0) {
            ui = create_virtual_from(src, virtual_name);
            if (ui < 0) {
                close(src);
                return 1;
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

        int rc = forward(src, ui);
        ioctl(src, EVIOCGRAB, 0);
        close(src);
        if (rc < 0) break;
        if (!g_stop) sleep_ms(RESCAN_INTERVAL_MS);
    }

    if (ui >= 0) {
        ioctl(ui, UI_DEV_DESTROY);
        close(ui);
    }
    logf_("exiting");
    return 0;
}
