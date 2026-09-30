// uitest4 <name> <gofile> <mode> [codes...]
// Fake pad for D-pad tests. Creates the device and then emits NOTHING until <gofile> exists.
//  mode "keys": then presses each given key code for 300ms, one after another.
//  mode "hat":  then sweeps HAT0X -1,0,+1,0 and HAT0Y -1,0,+1,0 (400ms steps).
#include <fcntl.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
static void emit(int fd, int t, int c, int v) { struct input_event e = {0}; e.type = t; e.code = c; e.value = v; write(fd, &e, sizeof e); }
static void syn(int fd) { emit(fd, EV_SYN, SYN_REPORT, 0); }
int main(int argc, char **argv) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { perror("uinput"); return 1; }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (int k = 0x130; k <= 0x13e; k++) ioctl(fd, UI_SET_KEYBIT, k);
    for (int k = 0x101; k <= 0x108; k++) ioctl(fd, UI_SET_KEYBIT, k);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    struct uinput_abs_setup a = {0};
    a.code = ABS_X; a.absinfo.minimum = -32767; a.absinfo.maximum = 32767; ioctl(fd, UI_SET_ABSBIT, ABS_X); ioctl(fd, UI_ABS_SETUP, &a);
    a.code = ABS_HAT0X; a.absinfo.minimum = -1; a.absinfo.maximum = 1; ioctl(fd, UI_SET_ABSBIT, ABS_HAT0X); ioctl(fd, UI_ABS_SETUP, &a);
    a.code = ABS_HAT0Y; ioctl(fd, UI_SET_ABSBIT, ABS_HAT0Y); ioctl(fd, UI_ABS_SETUP, &a);
    struct uinput_setup us = {0};
    us.id.bustype = 3; us.id.vendor = 0x1209; us.id.product = 0x0f04; us.id.version = 1;
    snprintf(us.name, UINPUT_MAX_NAME_SIZE, "%s", argv[1]);
    ioctl(fd, UI_DEV_SETUP, &us); ioctl(fd, UI_DEV_CREATE);
    for (int i = 0; i < 1200 && access(argv[2], F_OK) != 0; i++) usleep(100000);  // wait up to 2 min
    if (access(argv[2], F_OK) != 0) { ioctl(fd, UI_DEV_DESTROY); return 2; }
    if (!strcmp(argv[3], "keys")) {
        for (int i = 4; i < argc; i++) {
            int c = atoi(argv[i]);
            emit(fd, EV_KEY, c, 1); syn(fd); usleep(300000);
            emit(fd, EV_KEY, c, 0); syn(fd); usleep(300000);
        }
    } else {
        int seq[4] = {-1, 0, 1, 0};
        for (int axis = 0; axis < 2; axis++)
            for (int i = 0; i < 4; i++) {
                emit(fd, EV_ABS, axis == 0 ? ABS_HAT0X : ABS_HAT0Y, seq[i]); syn(fd); usleep(400000);
            }
    }
    sleep(2);
    ioctl(fd, UI_DEV_DESTROY);
    return 0;
}
