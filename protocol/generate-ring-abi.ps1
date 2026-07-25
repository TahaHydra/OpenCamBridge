param([switch]$Check)

$ErrorActionPreference = "Stop"
$protocolRoot = $PSScriptRoot
$repoRoot = Split-Path $protocolRoot -Parent
$schemaPath = Join-Path $protocolRoot "ring-abi.schema.json"
$schema = Get-Content -LiteralPath $schemaPath -Raw | ConvertFrom-Json

function Canonical-Fields($fields) {
    return ($fields | ForEach-Object { "$($_.name)@$($_.offset):$($_.type):$($_.size)" }) -join ";"
}

$canonical = "name=$($schema.name);version=$($schema.version);packing=$($schema.packing);headerSize=$($schema.headerSize);slotHeaderSize=$($schema.slotHeaderSize);slotCount=$($schema.slotCount);header=$(Canonical-Fields $schema.header);slot=$(Canonical-Fields $schema.slotHeader)"
$sha = [Security.Cryptography.SHA256]::Create()
try { $digest = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)) } finally { $sha.Dispose() }
$fingerprint = [BitConverter]::ToUInt64($digest, 0)
$fingerprintHex = "0x{0:x16}" -f $fingerprint
$shaHex = -join ($digest | ForEach-Object { $_.ToString("x2") })

$rustTypes = @{
    u16 = "u16"; u32 = "u32"; u64 = "u64";
    atomic_u32 = "std::sync::atomic::AtomicU32";
    atomic_i32 = "std::sync::atomic::AtomicI32";
    atomic_u64 = "std::sync::atomic::AtomicU64";
    bytes16 = "[u8; 16]"; bytes32 = "[u8; 32]";
    u64x1 = "[u64; 1]"; u64x2 = "[u64; 2]"; u64x6 = "[u64; 6]"; u64x7 = "[u64; 7]"
}

# The cursor arithmetic is emitted rather than hand-written in each language.
# Three consumers need it (the producer's own accounting, the C++ virtual camera and
# the Tauri preview) and they must agree exactly on which slot holds a given write, or
# one of them reads a different frame than the one it reports. The Rust copy carries
# the tests; this generator is what keeps the others identical to it.
#
# The window is deliberately one slot shorter than slotCount, because the producer's
# next write targets the oldest slot and a cursor must never point at it.
$rustSelection = @'

/// What a consumer holding a cursor should read from the history ring.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)]
pub struct RingSelection {
    /// Ring write sequence to read.
    pub ring_sequence: u64,
    /// Slot holding it.
    pub slot_index: usize,
    /// Ring writes between the cursor and this one that the consumer never saw.
    pub skipped: u64,
    /// How many of `skipped` the ring had already destroyed, so no amount of catching
    /// up could have recovered them. Separates "the ring is too short" from "this
    /// consumer read at the wrong moment".
    pub overwritten: u64,
    /// The producer restarted or changed geometry; cursor state is meaningless, so
    /// losses are reported as zero rather than as an enormous bogus figure.
    pub generation_changed: bool,
    /// False when the consumer has already seen this frame, so delivering it is a
    /// repeat. A caught-up consumer still gets a selection: Media Foundation must be
    /// handed a sample for every request, so "nothing new" has to mean "send the last
    /// one again", never "fail the request".
    pub fresh: bool,
}

/// Pick the newest committed frame and account for what the cursor missed.
///
/// This still selects the NEWEST frame, exactly as the pre-cursor code did, so what
/// the user sees is unchanged. What is new is the accounting, which makes "this
/// consumer skipped two frames" and "this frame was destroyed before anyone read it"
/// separately countable. Reading in cursor order instead of newest-first is a later
/// change, not this one.
#[allow(dead_code)]
pub fn ring_select_newest(
    cursor_next: u64,
    cursor_generation: u64,
    write_sequence: u64,
    generation: u64,
    slot_count: u64,
) -> Option<RingSelection> {
    if slot_count < 2 || write_sequence == 0 {
        return None;
    }
    let slot_index = ((write_sequence - 1) % slot_count) as usize;
    if generation != cursor_generation {
        return Some(RingSelection {
            ring_sequence: write_sequence,
            slot_index,
            skipped: 0,
            overwritten: 0,
            generation_changed: true,
            fresh: true,
        });
    }
    let fresh = cursor_next <= write_sequence;
    let oldest_safe = write_sequence.saturating_sub(slot_count - 2).max(1);
    let skipped = write_sequence.saturating_sub(cursor_next);
    let overwritten = if fresh {
        oldest_safe.saturating_sub(cursor_next)
    } else {
        0
    };
    Some(RingSelection {
        ring_sequence: write_sequence,
        slot_index,
        skipped,
        overwritten,
        generation_changed: false,
        fresh,
    })
}
'@

$cppSelection = @'

// What a consumer holding a cursor should read from the history ring. See the Rust
// `ring_select_newest`, which carries the tests for this arithmetic.
struct OpenCamBridgeRingSelection {
    uint64_t ringSequence;
    uint32_t slotIndex;
    // Ring writes between the cursor and this one that this consumer never saw.
    uint64_t skipped;
    // How many of `skipped` the ring had already destroyed.
    uint64_t overwritten;
    // The producer restarted or changed geometry; cursor state is meaningless.
    bool generationChanged;
    // False when this consumer has already seen the frame, so delivering it is a
    // repeat. A caught-up consumer still receives a selection, because Media
    // Foundation must be handed a sample for every request it makes.
    bool fresh;
    bool valid;
};

inline OpenCamBridgeRingSelection OcbSelectNewestRingFrame(
    uint64_t cursorNext, uint64_t cursorGeneration,
    uint64_t writeSequence, uint64_t generation, uint64_t slotCount)
{
    OpenCamBridgeRingSelection selection = {};
    if (slotCount < 2 || writeSequence == 0) return selection;
    selection.valid = true;
    selection.ringSequence = writeSequence;
    selection.slotIndex = static_cast<uint32_t>((writeSequence - 1) % slotCount);
    if (generation != cursorGeneration) {
        selection.generationChanged = true;
        selection.fresh = true;
        return selection;
    }
    selection.fresh = cursorNext <= writeSequence;
    const uint64_t window = slotCount - 2;
    const uint64_t oldestSafe = writeSequence > window ? writeSequence - window : 1;
    selection.skipped = writeSequence > cursorNext ? writeSequence - cursorNext : 0;
    selection.overwritten =
        (selection.fresh && oldestSafe > cursorNext) ? oldestSafe - cursorNext : 0;
    return selection;
}
'@

function Rust-Assertions([string]$headerType, [string]$slotType) {
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add("// Generated by protocol/generate-ring-abi.ps1. Do not edit.")
    $lines.Add("// Canonical source: protocol/ring-abi.schema.json")
    $lines.Add("pub const RING_ABI_HASH: u64 = $($fingerprintHex.Replace('0x','0x'));" )
    $lines.Add("pub const RING_ABI_LAYOUT_SHA256: &str = `"$shaHex`";")
    $lines.Add("pub const RING_HEADER_SIZE: usize = $($schema.headerSize);")
    $lines.Add("pub const SLOT_HEADER_SIZE: usize = $($schema.slotHeaderSize);")
    $lines.Add("pub const SLOT_COUNT: usize = $($schema.slotCount);")
    $lines.Add("const _: () = assert!(std::mem::size_of::<$headerType>() == RING_HEADER_SIZE);")
    $lines.Add("const _: () = assert!(std::mem::size_of::<$slotType>() == SLOT_HEADER_SIZE);")
    foreach ($field in $schema.header) {
        $type = $rustTypes[$field.type]
        $lines.Add("const _: fn(&$headerType) -> &$type = |value| &value.$($field.name);")
        $lines.Add("const _: () = assert!(std::mem::offset_of!($headerType, $($field.name)) == $($field.offset));")
        $lines.Add("const _: () = assert!(std::mem::size_of::<$type>() == $($field.size));")
    }
    foreach ($field in $schema.slotHeader) {
        $type = $rustTypes[$field.type]
        $lines.Add("const _: fn(&$slotType) -> &$type = |value| &value.$($field.name);")
        $lines.Add("const _: () = assert!(std::mem::offset_of!($slotType, $($field.name)) == $($field.offset));")
        $lines.Add("const _: () = assert!(std::mem::size_of::<$type>() == $($field.size));")
    }
    $lines.Add($rustSelection.Replace("`r`n", "`n"))
    return ($lines -join "`n") + "`n"
}

$cpp = [Collections.Generic.List[string]]::new()
$cpp.Add("// Generated by protocol/generate-ring-abi.ps1. Do not edit.")
$cpp.Add("// Canonical source: protocol/ring-abi.schema.json")
$cpp.Add("#pragma once")
$cpp.Add("#define OCBR_ABI_HASH $($fingerprintHex)ULL")
$cpp.Add("#define OCBR_ABI_LAYOUT_SHA256 `"$shaHex`"")
$cpp.Add("static_assert(sizeof(OpenCamBridgeRingHeader) == $($schema.headerSize), `"ring header size changed`" );")
$cpp.Add("static_assert(sizeof(OpenCamBridgeSlotHeader) == $($schema.slotHeaderSize), `"slot header size changed`" );")
foreach ($field in $schema.header) {
    $cpp.Add("static_assert(offsetof(OpenCamBridgeRingHeader, $($field.cpp)) == $($field.offset), `"ring field offset changed: $($field.cpp)`" );")
    $cpp.Add("static_assert(sizeof(((OpenCamBridgeRingHeader*)0)->$($field.cpp)) == $($field.size), `"ring field size changed: $($field.cpp)`" );")
}
foreach ($field in $schema.slotHeader) {
    $cpp.Add("static_assert(offsetof(OpenCamBridgeSlotHeader, $($field.cpp)) == $($field.offset), `"slot field offset changed: $($field.cpp)`" );")
    $cpp.Add("static_assert(sizeof(((OpenCamBridgeSlotHeader*)0)->$($field.cpp)) == $($field.size), `"slot field size changed: $($field.cpp)`" );")
}
$cpp.Add($cppSelection.Replace("`r`n", "`n"))
$cppContent = ($cpp -join "`n") + "`n"

$outputs = @{
    (Join-Path $repoRoot "windows/virtual-camera-mediafoundation/VirtualCameraMediaSource/RingAbi.generated.h") = $cppContent
    (Join-Path $repoRoot "windows/virtual-camera-mediafoundation/rust-frame-producer/src/ring_abi_generated.rs") = (Rust-Assertions "OpenCamBridgeRingHeader" "OpenCamBridgeSlotHeader")
    (Join-Path $repoRoot "desktop/tauri-app/src-tauri/src/ring_abi_generated.rs") = (Rust-Assertions "RingHeader" "SlotHeader")
}

foreach ($entry in $outputs.GetEnumerator()) {
    if ($Check) {
        if (!(Test-Path -LiteralPath $entry.Key) -or (Get-Content -LiteralPath $entry.Key -Raw) -ne $entry.Value) {
            throw "Generated ring ABI file is stale: $($entry.Key). Run .\\protocol\\generate-ring-abi.ps1"
        }
    } else {
        [IO.File]::WriteAllText($entry.Key, $entry.Value, [Text.UTF8Encoding]::new($false))
        Write-Host "Generated $($entry.Key)"
    }
}

Write-Host "Ring ABI fingerprint $fingerprintHex (SHA-256 $shaHex)"
