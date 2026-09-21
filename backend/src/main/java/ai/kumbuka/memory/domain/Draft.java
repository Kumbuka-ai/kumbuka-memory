package ai.kumbuka.memory.domain;

/**
 * The values a new entry is laid down with.
 *
 * <p>The address is not among them. It is the call's target, not a value of
 * the entry — with the one exception that the key IS stored, which is why
 * {@code create} takes it and {@code read} answers it while the address is
 * derived from it rather than stored beside it.
 *
 * @param type      one of the six, by its name
 * @param content   the entry itself
 * @param reference an optional provenance pointer; null where there is none
 */
public record Draft(String type, String content, String reference) {
}
