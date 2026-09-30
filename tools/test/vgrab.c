// vgrab <seconds> <readyfile>
// Test helper. Finds the "CouchMode Virtual Gamepad" input node, takes EXCLUSIVE control of it
// (EVIOCGRAB) so Android receives none of its events, creates <readyfile>, then prints every
// non-SYN event as "type code value" for <seconds> and exits (releasing the grab).
// Start this BEFORE any fake input exists, and only emit fake input after <readyfile> appears.
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
int main(int argc, char **argv) {
    int secs = atoi(argv[1]);
    int fd = -1;
    DIR *d = opendir("/dev/input");
    struct dirent *e;
    while (d && (e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char path[64], name[256] = {0};
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int f = open(path, O_RDONLY);
        if (f < 0) continue;
        struct input_id id;
        if (ioctl(f, EVIOCGNAME(sizeof name - 1), name) >= 0 && ioctl(f, EVIOCGID, &id) == 0 &&
            !strcmp(name, "CouchMode Virtual Gamepad") && id.vendor == 0x1209) { fd = f; break; }
        close(f);
    }
    if (fd < 0) { fprintf(stderr, "virtual gamepad not found\n"); return 1; }
    if (ioctl(fd, EVIOCGRAB, 1) < 0) { perror("EVIOCGRAB"); return 1; }
    FILE *rf = fopen(argv[2], "w"); if (rf) { fputs("ready\n", rf); fclose(rf); }
    time_t end = time(NULL) + secs;
    while (time(NULL) < end) {
        struct pollfd p = {fd, POLLIN, 0};
        if (poll(&p, 1, 200) > 0) {
            struct input_event ev[32];
            int n = read(fd, ev, sizeof ev);
            for (int i = 0; i < n / (int)sizeof ev[0]; i++)
                if (ev[i].type != EV_SYN) { printf("%d %d %d\n", ev[i].type, ev[i].code, ev[i].value); fflush(stdout); }
        }
    }
    ioctl(fd, EVIOCGRAB, 0);
    return 0;
}
