package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.domain.DigestView;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.Listing;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.memory.domain.NextStep;
import ai.kumbuka.memory.domain.Patch;
import ai.kumbuka.memory.surface.SurfaceException;
import ai.kumbuka.memory.surface.VerbSurface;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The wire, and nothing but the wire.
 *
 * <p>Every shape a caller sends or reads is declared here as a record, so that
 * the contract is a file somebody can open rather than a set of map literals
 * spread through a resource class. The names on the wire are written out with
 * {@code @JsonProperty} rather than left to a naming strategy: the strategy is
 * a global setting, and a global setting is a thing that changes every name at
 * once when somebody adjusts it for one.
 *
 * <h2>One projection, two paths</h2>
 *
 * {@link EntryFields#of} is the only place a stored entry becomes a field map.
 * A stale-token refusal carries the entry as the caller's own {@code read}
 * would answer it (DEC-0041), and it gets there through this same method — a
 * second projection on the refusal path is exactly what that node forbids,
 * because the two would eventually answer different field sets and the
 * refusal would be describing a different object.
 *
 * <h2>No uuid leaves through here</h2>
 *
 * There is no field of any record below that carries one. The address is
 * speaking, the scope is a slug, the type and the state are names. That is
 * asserted over every answer of the suite rather than argued for here.
 */
public final class Payloads {

    private Payloads() {
    }

    // ======================================================================
    // What a caller sends
    // ======================================================================

    /**
     * The body of a {@code create}.
     *
     * <p>Everything written is under {@code fields} (DEC-0040); the scope and
     * the selector are the address and stay in the path. The key is under
     * {@code fields} because it is stored on the entry and answered by
     * {@code read} — it is a value, and the address is derived from it.
     */
    public record CreateRequest(@JsonProperty("fields") EntryFields fields) {
    }

    /** The body of an {@code update}. The conflict token is a header, not a value. */
    public record UpdateRequest(@JsonProperty("fields") EntryFields fields) {
    }

    /**
     * The body of a {@code digest}.
     *
     * <p>One member, and it overrides rather than configures: a digest asked
     * with types answers for those types once, and the estate's stored
     * selection is unchanged. There is no write surface for that selection.
     */
    public record DigestRequest(@JsonProperty("types") List<String> types) {
    }

    // ======================================================================
    // What a caller reads
    // ======================================================================

    /**
     * The fields of an entry, in both directions.
     *
     * <p>The same record a caller writes and a caller reads, which is what
     * DEC-0040 asks for: a writing verb takes its values keyed by the names
     * {@code read} answers with. The fields a write may not set are refused by
     * the verb rather than left out of this type — a caller that sent
     * {@code scope} needs to be told it is fixed, and a type that could not
     * carry it could not tell it anything.
     */
    public record EntryFields(
        @JsonProperty("scope") String scope,
        @JsonProperty("key") String key,
        @JsonProperty("type") String type,
        @JsonProperty("content") String content,
        @JsonProperty("reference") String reference,
        @JsonProperty("state") String state,
        @JsonProperty("private") Boolean isPrivate,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

        /** The one projection of a stored entry. Used by every answer and every refusal. */
        public static EntryFields of(EntryView entry) {
            return new EntryFields(entry.scope(), entry.key(), entry.type(), entry.content(),
                entry.reference(), entry.state(), entry.isPrivate(),
                entry.createdAt(), entry.updatedAt());
        }

        /** The names a caller may send. Everything else in a body is refused by name. */
        public static final Set<String> WRITABLE = Set.of("type", "content", "reference");

        /** The names that exist but are fixed for the life of an entry. */
        public static final Set<String> IMMUTABLE = Set.of("scope", "key", "state",
            "private", "created_at", "updated_at");
    }

    /** One call open to this caller, and what it does. */
    public record NextPayload(@JsonProperty("call") String call,
                              @JsonProperty("does") String does) {

        public static List<NextPayload> of(List<NextStep> steps) {
            return steps.stream().map(s -> new NextPayload(s.call(), s.does())).toList();
        }
    }

    /**
     * An answer about one entry.
     *
     * <p>{@code address} and {@code fields}, the conflict token beside them and
     * never inside them, and the steps open to this caller beside both. The
     * token guards the write and is not written; the steps are computed per
     * caller and are not values of the entry.
     */
    public record EntryResponse(
        @JsonProperty("address") String address,
        @JsonProperty("fields") EntryFields fields,
        @JsonProperty("conflict_token") String conflictToken,
        @JsonProperty("next") List<NextPayload> next) {

        public static EntryResponse of(VerbSurface.Answer answer) {
            return new EntryResponse(
                answer.entry().address().canonical(),
                EntryFields.of(answer.entry()),
                answer.entry().conflictToken(),
                NextPayload.of(answer.next()));
        }
    }

    /**
     * One entry inside a listing.
     *
     * <p>Every entry carries its own address, its own token and its own next
     * steps (DEC-0040). A listing that carried one token for the set would be
     * handing out a token about the wrong thing.
     */
    public record ListedEntry(
        @JsonProperty("address") String address,
        @JsonProperty("fields") EntryFields fields,
        @JsonProperty("conflict_token") String conflictToken,
        @JsonProperty("next") List<NextPayload> next) {

        public static ListedEntry of(EntryView entry) {
            return new ListedEntry(entry.address().canonical(), EntryFields.of(entry),
                entry.conflictToken(), NextPayload.of(VerbSurface.nextFor(entry)));
        }
    }

    /**
     * A page of entries, with every reason it might not be the whole set.
     *
     * <p>{@code unaddressable} is not padding. An entry whose key carries no
     * selector has no address on this surface, so it cannot be listed — and a
     * listing that dropped it silently would be lying about its own
     * completeness. {@code create} refuses such a key, so a non-zero number
     * here is a statement about data that arrived from somewhere else.
     */
    public record ListingResponse(
        @JsonProperty("entries") List<ListedEntry> entries,
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("total") long total,
        @JsonProperty("page_size") int pageSize,
        @JsonProperty("cursor") String cursor,
        @JsonProperty("order") String order,
        @JsonProperty("unaddressable") long unaddressable) {

        public static ListingResponse of(Listing listing) {
            return new ListingResponse(
                listing.entries().stream().map(ListedEntry::of).toList(),
                listing.truncated(), listing.total(), listing.pageSize(),
                listing.cursor(), listing.order(), listing.unaddressable());
        }
    }

    /**
     * The answer to a withdrawal.
     *
     * <p>No {@code fields}, and the absence is the answer: in this edition the
     * entry is gone, so there is nothing to project. What the caller gets
     * instead is which of the two things happened — destroyed or retired —
     * and whether the address is free again, which is what decides whether
     * {@code create} is open on it next.
     */
    public record WithdrawnResponse(
        @JsonProperty("address") String address,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("address_released") boolean addressReleased,
        @JsonProperty("next") List<NextPayload> next) {

        public static WithdrawnResponse of(MemoryService.Withdrawn withdrawn) {
            String address = withdrawn.address().canonical();
            List<NextPayload> next = withdrawn.addressReleased()
                ? List.of(new NextPayload(
                    "create memory://" + withdrawn.address().scope() + "/"
                        + withdrawn.address().selector(),
                    "the address is free again; a new entry may be laid down at it"))
                : List.of(new NextPayload("read " + address,
                    "reads the retired entry, which still stands at this address"));

            return new WithdrawnResponse(address,
                withdrawn.outcome().name().toLowerCase(Locale.ROOT),
                withdrawn.addressReleased(), next);
        }
    }

    // ======================================================================
    // The digest
    // ======================================================================

    /** How much of one type there is, and whether this digest carries it. */
    public record TypeTallyPayload(
        @JsonProperty("type") String type,
        @JsonProperty("count") long count,
        @JsonProperty("token_estimate") long tokenEstimate,
        @JsonProperty("carried") boolean carried) {
    }

    /** One entry in a digest. No reference and no token: a digest is for reading. */
    public record DigestEntryPayload(
        @JsonProperty("address") String address,
        @JsonProperty("key") String key,
        @JsonProperty("content") String content) {
    }

    /** The entries of one type, oldest first. */
    public record DigestSectionPayload(
        @JsonProperty("type") String type,
        @JsonProperty("entries") List<DigestEntryPayload> entries) {
    }

    /**
     * Everything in force in a scope.
     *
     * <p>{@code address} is the scope's own, complete, so that the answer can
     * be handed back. The summary answers for all six types whatever the
     * selection is; the sections carry only the selected ones, and each tally
     * says which it was.
     */
    public record DigestResponse(
        @JsonProperty("address") String address,
        @JsonProperty("scopes") List<String> scopes,
        @JsonProperty("selected_types") List<String> selectedTypes,
        @JsonProperty("summary") List<TypeTallyPayload> summary,
        @JsonProperty("total_entries") long totalEntries,
        @JsonProperty("total_token_estimate") long totalTokens,
        @JsonProperty("sections") List<DigestSectionPayload> sections,
        @JsonProperty("unaddressable") long unaddressable,
        @JsonProperty("truncated") boolean truncated) {

        public static DigestResponse of(DigestView digest) {
            return new DigestResponse(
                "memory://" + digest.scope(),
                digest.scopes(),
                digest.selectedTypes(),
                digest.summary().stream()
                    .map(t -> new TypeTallyPayload(t.type(), t.count(), t.tokens(),
                        t.carried()))
                    .toList(),
                digest.totalEntries(),
                digest.totalTokens(),
                digest.sections().stream()
                    .map(section -> new DigestSectionPayload(section.type(),
                        section.entries().stream()
                            .map(e -> new DigestEntryPayload(e.address().canonical(),
                                e.key(), e.content()))
                            .toList()))
                    .toList(),
                digest.unaddressable(),
                // Answered as a constant false, and stated rather than left
                // out: a digest never cuts, and a member that is always false
                // is how a caller can see that rather than having to trust it.
                false);
        }
    }

    // ======================================================================
    // The refusal
    // ======================================================================

    /**
     * One envelope for every refusal, whichever half of the surface made it.
     *
     * <p>{@code { reason, message }} with the machine-readable detail under
     * {@code data} (DEC-0042). The not-found class is collapsed here and
     * nowhere else.
     */
    public record Refusal(
        @JsonProperty("reason") String reason,
        @JsonProperty("message") String message,
        @JsonProperty("data") Map<String, Object> data) {

        /** The one code the not-found class carries. */
        public static final String NOT_FOUND = "NOT_FOUND";

        /**
         * The one message, as a constant rather than a format.
         *
         * <p>Anything interpolated into it — a slug, an address, a count —
         * would make the answers differ in the bytes, which is exactly the
         * enumeration oracle the single code exists to close. It names what to
         * check without naming which of the cases happened.
         */
        static final String NOT_FOUND_MESSAGE =
            "there is nothing at this address for this caller. Either no entry stands "
                + "there, or it stands in a scope this caller cannot enter, or it is "
                + "another author's private entry — the answer is deliberately the same "
                + "for all of them, because one that told them apart would let a caller "
                + "map what it may not see. Check the address, and check that the scope "
                + "is one this caller belongs to.";

        /**
         * The reasons that answer as the not-found class.
         *
         * <p>Three, and the line between them and everything else is the one
         * DEC-0042 draws: an address that names nothing, an address in a scope
         * that is not this caller's to see, and — folded into the first by the
         * statement that produced it — another author's private entry. A
         * reason describing a scope the caller CAN see is not in here: a lock
         * and a missing write right reveal nothing a caller could not already
         * learn by having the scope answer at all, and collapsing them would
         * take away the one thing a refusal is for.
         */
        private static final Set<MemoryException.Reason> NOT_FOUND_REASONS = Set.of(
            MemoryException.Reason.ENTRY_ABSENT,
            MemoryException.Reason.SCOPE_UNRESOLVED);

        /** A refusal with a reason and a message and nothing machine-readable. */
        public static Refusal of(String reason, String message) {
            return new Refusal(reason, message, null);
        }

        /** The wire form of a transport refusal. */
        public static Refusal of(SurfaceException e) {
            return new Refusal(e.reason().name(), e.getMessage(), null);
        }

        /**
         * The wire form of a domain refusal — the one place the mapping lives.
         *
         * <p>The not-found collapse and the placement of the detail are
         * properties of the envelope. Building the envelope twice is how two
         * paths come to differ on one of them.
         */
        public static Refusal of(MemoryException e) {
            if (NOT_FOUND_REASONS.contains(e.reason())) {
                return new Refusal(NOT_FOUND, NOT_FOUND_MESSAGE, null);
            }
            return new Refusal(e.reason().name(), e.getMessage(), wireData(e));
        }

        /**
         * The refusal's detail, with any entry in it projected the way
         * {@code read} projects one.
         *
         * <p>A stale-token refusal carries the entry as this caller's own read
         * would answer it. It arrives here as the domain's own view type, and
         * this is where it becomes fields — through the same method every
         * answer uses, which is what stops the refusal path growing a
         * projection of its own.
         */
        private static Map<String, Object> wireData(MemoryException e) {
            if (e.data().isEmpty()) {
                return null;
            }
            Map<String, Object> wire = new LinkedHashMap<>();
            e.data().forEach((name, value) -> wire.put(name,
                value instanceof EntryView entry ? EntryFields.of(entry) : value));
            return wire;
        }
    }

    // ======================================================================
    // Turning a request body into what a verb takes
    // ======================================================================

    /**
     * The values of a {@code create}, refusing a body that sets what it may not.
     *
     * <p>Named rather than ignored. A caller that sent {@code state: "draft"}
     * and got a published entry would have been overruled in silence, and
     * would find out from the data rather than from the answer.
     */
    public static VerbSurface.NewEntry newEntry(CreateRequest request) {
        EntryFields fields = requireFields(request == null ? null : request.fields());
        refuseImmutable(fields, true);
        return new VerbSurface.NewEntry(fields.key(), fields.type(), fields.content(),
            fields.reference());
    }

    /**
     * The patch of an {@code update}.
     *
     * <p>A field is "mentioned" when the body carries it. Jackson gives an
     * absent member and a member set to null the same value, so the two cannot
     * be told apart on a record — which matters for {@code reference}, where
     * "leave it" and "clear it" are different instructions. The body therefore
     * carries the distinction explicitly: a reference is cleared by sending
     * the empty string, which is not a value a reference may otherwise have.
     */
    public static Patch patch(UpdateRequest request) {
        EntryFields fields = requireFields(request == null ? null : request.fields());
        refuseImmutable(fields, false);

        return new Patch(
            Optional.ofNullable(fields.type()),
            Optional.ofNullable(fields.content()),
            Optional.ofNullable(fields.reference())
                .map(reference -> reference.isEmpty() ? null : reference));
    }

    /** The types a digest was asked to carry, or null for the estate's selection. */
    public static List<String> digestTypes(DigestRequest request) {
        return request == null ? null : request.types();
    }

    private static EntryFields requireFields(EntryFields fields) {
        if (fields == null) {
            throw new MemoryException(MemoryException.Reason.UPDATE_EMPTY,
                "this verb takes the values it writes under 'fields', and the body "
                    + "carries none. The writable names are "
                    + EntryFields.WRITABLE.stream().sorted().toList() + ".");
        }
        return fields;
    }

    /**
     * Refuses a body that sets a field the verb does not write.
     *
     * @param creating whether the key is admissible, which it is on a create
     *                 and never afterwards
     */
    private static void refuseImmutable(EntryFields fields, boolean creating) {
        List<String> offenders = new java.util.ArrayList<>();
        if (fields.scope() != null) {
            offenders.add("scope");
        }
        if (fields.key() != null && !creating) {
            offenders.add("key");
        }
        if (fields.state() != null) {
            offenders.add("state");
        }
        if (fields.isPrivate() != null) {
            offenders.add("private");
        }
        if (fields.createdAt() != null) {
            offenders.add("created_at");
        }
        if (fields.updatedAt() != null) {
            offenders.add("updated_at");
        }
        if (offenders.isEmpty()) {
            return;
        }
        throw MemoryException.offending(MemoryException.Reason.FIELD_IMMUTABLE,
            "these fields are not written by this verb. " + offenders + " "
                + (offenders.size() == 1 ? "is" : "are") + " fixed for the life of an "
                + "entry or derived by the service: the scope and the key are where the "
                + "entry stands, 'private' follows the scope's kind, and the two "
                + "timestamps are the service's record of what it did. The writable "
                + "names are " + EntryFields.WRITABLE.stream().sorted().toList() + ".",
            offenders);
    }
}
