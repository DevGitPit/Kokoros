
import sys

def patch_needed(path, old_name, new_name):
    with open(path, 'rb') as f:
        data = f.read()

    old_bytes = old_name.encode('utf-8')
    new_bytes = new_name.encode('utf-8')

    if len(new_bytes) != len(old_bytes):
        print(f"Error: New name must be same length as old name ({len(old_bytes)} chars).")
        # We can pad with nulls if shorter, but usually SONAMEs are exact.
        # libc++_shared.so is 16 chars. 
        # libkokoro_cpp.so is 16 chars! PERFECT.
        if len(new_bytes) > len(old_bytes):
            sys.exit(1)
        new_bytes = new_bytes + b'\x00' * (len(old_bytes) - len(new_bytes))

    count = data.count(old_bytes)
    if count == 0:
        print(f"Error: String '{old_name}' not found.")
        sys.exit(1)
    
    print(f"Found {count} occurrences. Patching...")
    new_data = data.replace(old_bytes, new_bytes)
    
    with open(path, 'wb') as f:
        f.write(new_data)
    print("Done.")

if __name__ == "__main__":
    patch_needed(sys.argv[1], sys.argv[2], sys.argv[3])
