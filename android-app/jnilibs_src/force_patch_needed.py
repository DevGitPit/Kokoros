
import sys

def force_patch(path):
    with open(path, 'rb') as f:
        data = bytearray(f.read())

    # We want to replace these strings with libdl.so (which is already a dependency and is small)
    targets = [b"libc++_shared.so", b"libstdc++.so"]
    replacement = b"libdl.so\x00"

    found = False
    for target in targets:
        # We look for the string in the dynstr table
        # Since libdl.so is shorter, we pad with nulls
        # BUT we must be careful not to break the string table structure.
        # Replacing the string content with libdl.so + nulls is usually safe
        # if we don't change the total length of the string table.
        
        idx = data.find(target)
        while idx != -1:
            print(f"Patching {target.decode()} at offset {idx}")
            new_val = replacement + b'\x00' * (len(target) - len(replacement) + 1)
            data[idx:idx+len(target)+1] = new_val[:len(target)+1]
            found = True
            idx = data.find(target, idx + 1)

    if found:
        with open(path, 'wb') as f:
            f.write(data)
        print("Successfully patched.")
    else:
        print("No target dependencies found to patch.")

if __name__ == "__main__":
    force_patch(sys.argv[1])
