/* Offline research probe: changes only its own allocated RAM and returns.
 * No OS imports, hardware access, recording, network, or persistent writes.
 * Hardware execution verified on installed G2 1.0.1, 23 October 2026.
 * See analysis/g2-live-1.0.1/module-test-20261023/RESULTS.txt.
 */
.syntax unified
.cpu cortex-m55
.thumb

.section .text,"ax",%progbits
.balign 4
.global g2_probe_init
.type g2_probe_init,%function
.thumb_func
g2_probe_init:
    ldr r0, marker_address
    ldr r1, marker_value
    str r1, [r0]
    bx lr
.size g2_probe_init, .-g2_probe_init
.balign 4
marker_value:
    .word 0x4b4f3247 /* Bytes "G2OK" in little endian. */
marker_address:
    .word g2_probe_marker

.section .data,"aw",%progbits
.balign 4
.global g2_probe_marker
.type g2_probe_marker,%object
g2_probe_marker:
    .word 0
.size g2_probe_marker, 4

.section .init_array,"aw",%init_array
.balign 4
    .word g2_probe_init

.section .note.GNU-stack,"",%progbits
