# Crash-safe worker journal: contract and bounded proof

Status: design/proof checkpoint. No journal implementation is authorized by
this slice.

Jolt-sim needs a self-validating append-only forensic event log, not a
transactional database recovery engine. We borrow ARIES's monotonically
increasing log-position idea, LevelDB's length-plus-CRC record framing,
SQLite's stop-at-first-invalid forward scan and checksum chain, and
PostgreSQL's total ordering and previous-record link. We deliberately omit
ARIES page LSNs, dirty-page and transaction tables, compensation records,
analysis/redo/undo passes, and fuzzy checkpoints.

Primary references:

- [ARIES paper](https://research.ibm.com/publications/aries-a-transaction-recovery-method-supporting-fine-granularity-locking-and-partial-rollbacks-using-write-ahead-logging)
- [LevelDB log format](https://github.com/google/leveldb/blob/main/doc/log_format.md)
  and [reader](https://github.com/google/leveldb/blob/main/db/log_reader.cc)
- [SQLite WAL recovery](https://www.sqlite.org/walformat.html) and
  [frame format](https://www.sqlite.org/fileformat2.html#walformat)
- [PostgreSQL WAL record header](https://github.com/postgres/postgres/blob/master/src/include/access/xlogrecord.h)
  and [WAL internals](https://www.postgresql.org/docs/current/wal-internals.html)
- Linux [`write(2)`](https://man7.org/linux/man-pages/man2/write.2.html) and
  [`fsync(2)`](https://man7.org/linux/man-pages/man2/fsync.2.html)

## Proposed format

All integers have one explicitly selected byte order. Version 1 will use big
endian unless implementation evidence reveals a concrete reason not to.
CRC means CRC32C. Each worker/run owns one uniquely named segment, and a
segment is never reused.

```text
segment header (36 bytes):
  magic[8] | format-u16 | header-length-u16 | max-payload-u32
  | run-id[16] | header-crc32c-u32

record (28-byte overhead):
  magic-u32 | version-u8 | kind-u8 | flags-u16
  | sequence-u64 | payload-length-u32 | previous-crc32c-u32
  | payload[payload-length] | record-crc32c-u32
```

The header checksum covers every preceding header byte. A record checksum
covers every preceding byte of that record, including sequence, length, and
previous checksum. Sequence starts at zero and increments by exactly one. The
first record's `previous-crc32c` equals the validated header checksum; later
records name the previous validated record checksum. The trailing record
checksum is the completion marker.

Payloads are bounded immutable canonical-EDN UTF-8 bytes prepared before the
writer lock. Live byte arrays, lazy sequences, exceptions, descriptors, and
host objects are never retained by the journal encoder. Format versioning is
internal prerelease machinery, not a compatibility promise.

## Writer and recovery contract

The writer has one exclusive owner per segment. It serializes a bounded frame,
takes the writer lock, and uses a full-write loop that handles partial writes
and `EINTR`. It never appends to a segment after any uncertain or invalid tail.
The initial implementation marks journal health failed and disables further
appends for that worker; multi-segment continuation is intentionally deferred.

Recovery is read-only. It validates the complete segment header, then scans
strictly forward. It stops at the first incomplete header, incomplete payload,
incomplete trailer, oversized length, unexpected sequence, broken previous-CRC
link, or checksum failure. It never repairs, truncates, or resynchronizes. It
returns at least:

```clojure
{:records [...]
 :last-good-offset n
 :raw-tail byte-array
 :failure-reason keyword-or-nil}
```

The bytes before `:last-good-offset` plus `:raw-tail` must equal the observed
file byte-for-byte. Any noncanonical salvage scan belongs in a separate tool.

Journal open, encode, write, flush, or sync failure changes journal health
only. It must not prevent scenario invocation, change an application result,
replace an application exception, or suppress cleanup.

## Durability modes

- `:process-crash` completes the full-write loop and flushes language buffers.
  It claims survival from worker termination only. It makes no power-loss or
  kernel-crash claim.
- `:power` additionally completes `fdatasync`/`fsync` according to policy.
  The writer tracks separate `accepted-end` and `synced-end` offsets; a failed
  or absent sync cannot advance `synced-end`. If durable segment naming is
  required, the containing directory is synced after creation.

Power loss is not modeled as a simple byte-prefix cut. Torn or reordered
storage writes require a later, separate fault model and explicit filesystem
assumptions.

## Proved bounded claims

The reference files negate their safety property and must be `unsat`. They use
unrolled scanner/state transitions or a two-execution noninterference relation;
the checked safety predicates are stated separately. Buggy controls and
boundary witnesses must be `sat`.

1. For zero to four records, payload lengths zero through eight, and every byte
   cut of the encoded segment, recovery accepts exactly the maximal complete
   initial prefix, accepts no partial record, and reports the exact last-good
   boundary.
2. A byte-complete header with bad magic, version, length, or checksum cannot
   advance the recovery cursor or admit a record.
3. Recovery cannot accept a later valid-looking record after a corrupt record,
   nor accept a record with a broken sequence or previous-checksum link.
4. The wire `u32` payload length is zero-extended before `u64` arithmetic. A
   declared length is accepted/allocated only when it is no greater than
   `max-payload`, cannot wrap when the trailer is added, and leaves enough
   observed bytes for payload and trailer.
5. Journal failures cannot prevent application invocation or change the
   application result/primary exception.
6. `accepted-end` and `synced-end` are monotone, `synced-end` never exceeds
   `accepted-end`, and only a completed power-mode barrier advances the durable
   boundary.
7. A bounded full-write loop never skips or duplicates bytes, `EINTR` advances
   no position, and `accepted-end` advances exactly once only after the whole
   frame is written.

The structural SMT model treats checksum equality as collision-free. It does
not prove CRC32C collision resistance, filesystem behavior, encoding
correctness, or the implementation. Those require executable corpus,
fault-injection, and crash-cut tests after the model is accepted.

The crash-cut model uses zero through four records. The chain-corruption model
uses three records, which is the smallest bound that places the first invalid
record at the beginning, middle, or end while leaving a later valid-looking
record for the resynchronization control. The length model uses a representative
64 KiB configured maximum; validating the header's chosen maximum is represented
by the header model's `length-valid` predicate and remains an executable policy
test. Failure isolation is conditional on the explicit catch-policy premises;
the required fault-injection tests must witness those premises in source.

Run the bounded gate with:

```sh
proofs/journal-wal/verify.sh
```

## Implementation gate

Before implementation lands, convert each SAT control and boundary witness
into a test against the real encoder/recovery scanner:

- cut immediately before the trailing CRC;
- corrupt a complete segment header, including its CRC;
- corrupt a middle record followed by a valid-looking record;
- delete/reorder a middle record or alter its previous checksum;
- zero and exact-maximum payloads;
- oversized, wrapped, and incomplete declared lengths without oversized
  allocation;
- journal open/write/flush/sync failures around both successful and throwing
  applications;
- partial writes and `EINTR` at each full-write-loop position;
- repeated recovery of identical bytes and exact raw-tail preservation;
- process termination after every byte of representative encoded segments;
- actual CRC32C vectors and single-bit corruptions.

Only after these witnesses exist should the current newline-EDN progress sink
be replaced by the framed journal.
