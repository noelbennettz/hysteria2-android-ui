/*
 * Host-side check for the `misc.block-tcp` / `misc.block-udp` patch
 * (native/tun2socks/0001-block-ports.patch). Not shipped in the app.
 *
 *   HEV=$(scripts/tun2socks.sh source)          # or any checkout + patches
 *   cd "$HEV" && make static
 *   gcc -O1 -o /tmp/block-ports-test \
 *       "$OLDPWD"/native/tun2socks/block-ports-test.c \
 *       bin/libhev-socks5-tunnel.a third-part/lwip/bin/liblwip.a \
 *       third-part/yaml/bin/libyaml.a third-part/hev-task-system/bin/libhev-task-system.a \
 *       -I src -I src/core/include -I third-part/yaml/include \
 *       -I third-part/lwip/src/include -I third-part/lwip/src/ports/include \
 *       -I third-part/hev-task-system/include -lpthread
 *   /tmp/block-ports-test
 */
#include <stdio.h>
#include <string.h>

#include "hev-config.h"

static int failures;

#define EXPECT(expr, want)                                                     \
    do {                                                                       \
        int got = (expr);                                                      \
        printf ("%-34s = %d (want %d)%s\n", #expr, got, want,                  \
                got == want ? "" : "  <-- FAIL");                              \
        if (got != want)                                                       \
            failures = 1;                                                      \
    } while (0)

int
main (void)
{
    const char *yaml = "misc:\n"
                       "  block-tcp: '25,465,8000-8100'\n"
                       "  block-udp: '443,53'\n";
    const char *bad = "misc:\n  block-udp: 'abc,-,0,70000,443-'\n";

    if (hev_config_init_from_str ((const unsigned char *)yaml, strlen (yaml)))
        return 1;

    EXPECT (hev_config_get_block_tcp (25), 1);
    EXPECT (hev_config_get_block_tcp (465), 1);
    EXPECT (hev_config_get_block_tcp (8000), 1);
    EXPECT (hev_config_get_block_tcp (8100), 1);
    EXPECT (hev_config_get_block_tcp (8101), 0);
    EXPECT (hev_config_get_block_tcp (24), 0);
    EXPECT (hev_config_get_block_tcp (443), 0);
    EXPECT (hev_config_get_block_udp (443), 1);
    EXPECT (hev_config_get_block_udp (53), 1);
    EXPECT (hev_config_get_block_udp (54), 0);
    EXPECT (hev_config_get_block_udp (25), 0);

    /* Malformed input must neither crash nor block anything. */
    hev_config_init_from_str ((const unsigned char *)bad, strlen (bad));
    EXPECT (hev_config_get_block_udp (443), 0);

    puts (failures ? "FAILED" : "OK");
    return failures;
}
