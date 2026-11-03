// ffplay <event node> [strong 0-65535] [weak 0-65535] [ms] [repeat_ms]
// Acts like Android's rumble request: uploads one FF_RUMBLE effect to the given input device, plays
// it once, waits for it to finish, then erases it. Used to test rumble forwarding: point it at the
// "CouchMode Virtual Gamepad" node and the ACTIVE SOURCE PAD should vibrate for [ms] milliseconds.
// Sends no button or stick events, so it can't press anything. Build with the NDK:
//   aarch64-linux-android26-clang tools/test/ffplay.c -o ffplay
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s /dev/input/eventN [strong] [weak] [ms]\n", argv[0]); return 2; }
    int strong = argc > 2 ? atoi(argv[2]) : 50000;
    int weak = argc > 3 ? atoi(argv[3]) : 30000;
    int ms = argc > 4 ? atoi(argv[4]) : 1000;
    int repeat = argc > 5 ? atoi(argv[5]) : 0;
    int fd = open(argv[1], O_RDWR);
    if (fd < 0) { perror("open"); return 1; }
    unsigned long ff[(FF_MAX + 8 * sizeof(long)) / (8 * sizeof(long))] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_FF, sizeof(ff)), ff) < 0 || !(ff[FF_RUMBLE / (8 * sizeof(long))] >> (FF_RUMBLE % (8 * sizeof(long))) & 1)) {
        fprintf(stderr, "device does not declare FF_RUMBLE\n");
        return 3;
    }
    struct ff_effect e;
    memset(&e, 0, sizeof(e));
    e.type = FF_RUMBLE;
    e.id = -1;
    e.u.rumble.strong_magnitude = strong;
    e.u.rumble.weak_magnitude = weak;
    e.replay.length = ms;
    if (ioctl(fd, EVIOCSFF, &e) < 0) { perror("EVIOCSFF"); return 4; }
    printf("uploaded effect id %d (strong %d, weak %d, %d ms)\n", e.id, strong, weak, ms);
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = EV_FF; ev.code = e.id; ev.value = 1;
    if (write(fd, &ev, sizeof(ev)) < 0) { perror("play"); return 5; }
    printf("playing\n");
    if (repeat > 0) {
        for (int t = 0; t < ms; t += repeat) {
            usleep(repeat * 1000);
            if (write(fd, &ev, sizeof(ev)) < 0) { perror("replay"); break; }
        }
        usleep(300 * 1000);
    } else {
        usleep((ms + 300) * 1000);
    }
    ioctl(fd, EVIOCRMFF, e.id);
    printf("erased\n");
    close(fd);
    return 0;
}
