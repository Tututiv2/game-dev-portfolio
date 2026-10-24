package com.thestill.admin;

import com.thestill.cosmetic.NickPrompt;

/**
 * The four things every {@code /adminset} page needs, in one object.
 *
 * <p>Navigation opens a new {@link AdminSetMenu} rather than mutating the open one,
 * which is the right idiom and also means every constructor is copied at every jump.
 * With this, a new page is three fields instead of nine, and adding a dependency does
 * not touch every {@code new AdminSetMenu(...)} in the file.
 *
 * <p>{@link NickPrompt} is deliberately the cosmetics one rather than a second copy:
 * it already solved arming a chat prompt correctly — close before arming, claim at
 * {@code LOWEST}, hop to the main thread, and lapse on a deadline instead of eating
 * whatever gets typed forty minutes later. Search before you create.
 */
public record AdminSetContext(AdminActions actions, NickPrompt prompt, AdminAudit audit,
                              ConfirmGate gate) {
}
