package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.EntryAddress;
import ai.kumbuka.memory.domain.MemoryException;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The address grammar: what a caller may write, decided without knowing a
 * scope.
 *
 * <h2>Why grammar runs before the scope is resolved</h2>
 *
 * The ratified check order answers the single not-found for a scope the caller
 * may not see, however broken the rest of the call is — so that the error path
 * cannot be used to enumerate scopes. Grammar is the deliberate exception,
 * because it is decidable without knowing a scope at all: refusing
 * {@code ..} as a selector reveals nothing about which scopes exist.
 *
 * <h2>Two grammars, and the narrower one binds</h2>
 *
 * The contract for this surface gives the selector as
 * {@code [a-z][a-z0-9_-]{0,15}} and the id as segments from the unreserved
 * character set. The table underneath gives the whole key as
 * {@code ^[a-z0-9]+([.-][a-z0-9]+)*$} — a check constraint carried over
 * unchanged in V1 and not editable here. The second is narrower: it admits
 * neither {@code _} nor {@code ~}, which the first does.
 *
 * <p>So both are checked and the key is refused unless it satisfies each. The
 * alternative — checking only the wider one — would let a key through to a
 * check constraint, where it becomes a constraint violation at flush time,
 * far from the call site and outside the typed refusal model. The narrowing is
 * reported as a finding rather than resolved here, because resolving it means
 * editing a migration.
 *
 * <p>The database's expression is restated here rather than read from the
 * migration. That is a duplication and it is the lesser evil: a check that
 * read its expectation out of the artefact it is checking would agree with it
 * by construction and could never disagree.
 *
 * <h2>Why the quantifiers are possessive</h2>
 *
 * {@code [a-z0-9]+([.-][a-z0-9]+)*} is the shape a catastrophic-backtracking
 * analysis flags: a repetition inside a repetition, which on a long
 * non-matching input can take exponential time. Here it cannot — the separator
 * class and the segment class share no character, so at every position exactly
 * one of the two can match and there is never an alternative for the engine to
 * come back to.
 *
 * <p>That argument is correct and it is also invisible to anything but a
 * reader, so the quantifiers are written possessively instead. A possessive
 * quantifier never gives characters back, which makes the linearity a property
 * of the pattern rather than of an argument about it. The accepted language is
 * unchanged — precisely because there was nothing to give back — and that
 * equivalence is asserted rather than claimed: {@code AddressGrammarTest} runs
 * both forms over one corpus and requires them to agree on every input.
 */
public final class AddressParser {

    /**
     * The key, as the table stores it: lower-case alphanumeric segments
     * separated by dots or dashes. Restated from {@code memory_key_format} in
     * V1.
     */
    private static final Pattern KEY =
        Pattern.compile("^[a-z0-9]++(?:[.-][a-z0-9]++)*+$");

    /** The selector, as the surface contract gives it. */
    private static final Pattern SELECTOR = Pattern.compile("^[a-z][a-z0-9_-]{0,15}$");

    /**
     * The scope name. Not given by the contract, so it is taken as what the
     * platform's slugs are: lower-case, alphanumeric, dash-separated. A scope
     * this refuses cannot exist, so refusing it discloses nothing.
     */
    private static final Pattern SCOPE =
        Pattern.compile("^[a-z0-9]++(?:-[a-z0-9]++)*+$");

    /**
     * The one selector this service keeps for itself.
     *
     * <p>Matched against the first dot- or dash-separated segment, so
     * {@code system.notes} and {@code system-notes} are both refused and
     * {@code systems.notes} is not.
     */
    private static final String RESERVED_SELECTOR = "system";

    private AddressParser() {
    }

    /**
     * Whether a string is a key this service stores.
     *
     * <p>Exposed so that the equivalence of the possessive form and the
     * ordinary one can be measured over a corpus rather than asserted in a
     * comment. It answers rather than refuses, because the comparison is about
     * the language and not about a caller.
     */
    static boolean acceptsKey(String candidate) {
        return candidate != null && KEY.matcher(candidate).matches();
    }

    /** Whether a string is a scope name of the shape the platform allocates. */
    static boolean acceptsScope(String candidate) {
        return candidate != null && SCOPE.matcher(candidate).matches();
    }

    /**
     * The address a caller named, or a typed refusal.
     *
     * <p>The id arrives as one path segment because a key holds no slash: the
     * id is whatever follows the first dot, dots included, and dots do not
     * divide a path. So there is no multi-segment id to reassemble here, and
     * the colon notation the sibling services need for their transitions is
     * needed here only to keep a verb apart from an id that could contain one.
     */
    public static EntryAddress parse(Parts parts) {
        return parse(parts.scope(), parts.selector(), parts.id());
    }

    public static EntryAddress parse(String scope, String selector, String id) {
        requireScope(scope);
        requireSelector(selector);
        String key = selector + EntryAddress.SEPARATOR + id;
        requireKey(key);
        return new EntryAddress(scope, selector, id);
    }

    /**
     * The address a key denotes in a scope, with both grammars checked.
     *
     * <p>Used by {@code create}, which takes the whole key as a value of the
     * entry: the key is what the table stores, so it is what a caller writes
     * and what {@code read} answers with.
     */
    public static EntryAddress ofKey(String scope, String key) {
        requireScope(scope);
        requireKey(key);
        EntryAddress address = EntryAddress.ofKey(scope, key);
        requireSelector(address.selector());
        return address;
    }

    /**
     * The entry a technical address names, where the caller wrote one.
     *
     * <p>{@code memory://<uuid>} carries no scope, so on this surface it
     * arrives as a single path segment where a scope would otherwise stand.
     * The two are told apart by form: a uuid parses as one and a scope slug
     * does not, because {@link #SCOPE} admits no segment of exactly the uuid
     * shape's length pattern — and where a cluster ever did allocate a scope
     * whose slug is a well-formed uuid, that scope would be reachable by every
     * verb except this one read. The collision is named rather than left for
     * somebody to discover.
     */
    public static Optional<UUID> technicalAddress(String segment) {
        if (segment == null || segment.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(segment));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /**
     * Refuses a write onto the reserved selector.
     *
     * <p>Only on a write. Reading a {@code system} entry is not refused: the
     * reservation is about who may lay one down, and a caller that can see one
     * is meant to be able to read it.
     */
    public static void requireWritableSelector(EntryAddress address) {
        String first = address.key().split("[.-]", 2)[0];
        if (RESERVED_SELECTOR.equals(first)) {
            throw new MemoryException(MemoryException.Reason.SELECTOR_RESERVED,
                "'" + RESERVED_SELECTOR + "' is reserved for entries this service lays "
                    + "down itself, so no verb writes one. The check is on the first dot- "
                    + "or dash-separated segment of the key, which is why '"
                    + address.key() + "' is refused. Choose another selector — "
                    + "'convention', 'decision' and 'constraint' are the ordinary ones.");
        }
    }

    /**
     * Refuses a key whose selector is not the one the address named.
     *
     * <p>{@code create} names the selector in the path and the key in the
     * body, and the key carries its own selector. Deriving one from the other
     * silently would mean a caller who wrote {@code POST /api/est/decision}
     * with {@code key: "convention.x"} got an entry under a selector they did
     * not write, in a place they would not look for it.
     */
    public static void requireAgreement(String selector, EntryAddress fromKey) {
        if (!selector.equals(fromKey.selector())) {
            throw new MemoryException(MemoryException.Reason.SELECTOR_MISMATCHED,
                "the address names the selector '" + selector + "' and the key '"
                    + fromKey.key() + "' carries '" + fromKey.selector() + "'. The key's "
                    + "selector is the part before its first dot and it is what decides "
                    + "where the entry stands, so the two have to be the same one. Either "
                    + "post to '" + fromKey.selector() + "' or write the key as '"
                    + selector + "." + fromKey.id() + "'.");
        }
    }

    private static void requireScope(String scope) {
        if (scope == null || !SCOPE.matcher(scope).matches()) {
            throw new MemoryException(MemoryException.Reason.ADDRESS_MALFORMED,
                "'" + scope + "' is not a scope name. A scope is written in lower-case "
                    + "alphanumeric segments separated by single dashes, which is what "
                    + "the platform allocates — so a name of another shape names no "
                    + "scope that could exist.");
        }
    }

    private static void requireSelector(String selector) {
        if (selector == null || !SELECTOR.matcher(selector).matches()) {
            throw new MemoryException(MemoryException.Reason.KEY_MALFORMED,
                "'" + selector + "' is not a selector. A selector begins with a letter, "
                    + "runs to at most sixteen characters, and carries lower-case "
                    + "letters, digits, dashes and underscores — it is the part of the "
                    + "key before the first dot and it is meant to be retyped from "
                    + "memory.");
        }
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new MemoryException(MemoryException.Reason.KEY_MALFORMED,
                "'" + key + "' is not a key this service stores. A key is lower-case "
                    + "alphanumeric segments joined by single dots or dashes, and the "
                    + "first dot is what separates the selector from the id — so "
                    + "'decision.storage-engine' is one and 'Decision_Storage' is not. "
                    + "The underscore the selector grammar admits is refused by the "
                    + "table underneath, and the narrower of the two binds.");
        }
    }

    /**
     * The path segments a call arrived on, before any of them is a thing.
     *
     * <p>Three strings in a record rather than three parameters threaded
     * through the surface: they travel together everywhere, and a signature
     * taking them separately is one where two of them can be swapped by a
     * caller and nothing will say so.
     *
     * @param scope    the first segment
     * @param selector the second
     * @param id       the third; a key holds no slash, so it is never more
     *                 than one segment
     */
    public record Parts(String scope, String selector, String id) {
    }
}
