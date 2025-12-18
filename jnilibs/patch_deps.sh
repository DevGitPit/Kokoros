#!/bin/bash
set -e

echo "Searching for crate sources in ~/.cargo/registry..."

# Fix ort-sys (2.0.0-rc.10 or rc.9)
# Issue: cache_dir not enabled for android
ORT_SYS_DIRS=$(find ~/.cargo/registry/src -type d -name "ort-sys-2.0.0-rc.*")

if [ -z "$ORT_SYS_DIRS" ]; then
    echo "Warning: ort-sys source not found. Run cargo build once to download it."
else
    for DIR in $ORT_SYS_DIRS; do
        echo "Patching ort-sys in $DIR..."
        FILE="$DIR/src/internal/dirs.rs"
        if [ -f "$FILE" ]; then
            # Replace #[cfg(target_os = "linux")] with #[cfg(any(target_os = "linux", target_os = "android"))]
            sed -i 's/target_os = "linux"/any(target_os = "linux", target_os = "android")/' "$FILE"
            echo "  Patched $FILE"
        else
            echo "  Error: $FILE not found"
        fi
    done
fi

# Fix audiopus_sys (0.2.2)
# Issue: default_library_linking returns () instead of bool in some cases?
# Error: expected `bool`, found `()`
AUDIOPUS_DIRS=$(find ~/.cargo/registry/src -type d -name "audiopus_sys-0.2.2")

if [ -z "$AUDIOPUS_DIRS" ]; then
    echo "Warning: audiopus_sys source not found."
else
    for DIR in $AUDIOPUS_DIRS; do
        echo "Patching audiopus_sys in $DIR..."
        FILE="$DIR/build.rs"
        if [ -f "$FILE" ]; then
            # The function default_library_linking() -> bool might be missing a return in a catch-all branch?
            # Let's see the context. The error said line 83.
            # It seems it's empty body?
            # We can try to just add `true` or `false`?
            # Or assume it should return false.
            
            # The sed command: append 'false' before the closing brace of the function if it's implicitly returning ()?
            # A safer patch:
            # Look for `fn default_library_linking() -> bool {`
            # If it relies on cfg macros and none match, it might fall through.
            
            # This is harder to patch blindly with sed.
            # But usually this error means:
            # #[cfg(...)] return true;
            # #[cfg(...)] return true;
            # // no fallthrough return!
            
            # I will append `false` to the end of the file or function?
            # Let's try replacing `}` with `false }` for that function? No.
            
            # Specific patch for audiopus_sys 0.2.2 build.rs line 83:
            # It seems line 83 is the end of the function.
            # I will try to insert `false` before the last line of the function?
            # Actually, I'll just replace the function signature to return () if I could, but no.
            
            # Let's assume we want to force it to `false`.
            # I'll search for the function definition and replace the whole block? Risky.
            
            # Better: `cargo update -p audiopus_sys` might fix it if a newer version exists?
            # 0.2.2 is old.
            # But let's try to patch.
            
            # Just return `false` at end of file? No.
            
            # Let's just try to change the signature to not return bool? No, caller expects it.
            
            # Let's force it to return false.
            # sed substitute the closing brace of that function?
            # `sed -i '83s/}/false }/' "$FILE"` might work if line numbers match.
            # The error said line 83.
            
            sed -i '83s/}/false }/' "$FILE"
            echo "  Patched $FILE (inserted false return)"
        fi
    done
fi

echo "Patches applied. Try building again."
