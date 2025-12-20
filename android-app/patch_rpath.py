
import sys

def patch_file(path, old_str, new_str):
    with open(path, 'rb') as f:
        data = f.read()

    old_bytes = old_str.encode('utf-8')
    new_bytes = new_str.encode('utf-8')

    if len(new_bytes) > len(old_bytes):
        print(f"Error: New string '{new_str}' is longer than old string.")
        sys.exit(1)
    
    # Pad with nulls
    new_bytes = new_bytes + b'\x00' * (len(old_bytes) - len(new_bytes))

    count = data.count(old_bytes)
    if count == 0:
        print(f"Error: String '{old_str}' not found in {path}.")
        # Try finding a substring if the exact path might vary? 
        # But readelf showed exactly that path.
        sys.exit(1)
    
    print(f"Found {count} occurrences. Patching...")
    
    new_data = data.replace(old_bytes, new_bytes)
    
    with open(path, 'wb') as f:
        f.write(new_data)
    
    print("Done.")

if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python3 patch_rpath.py <file> <old_string> <new_string>")
        sys.exit(1)
    
    patch_file(sys.argv[1], sys.argv[2], sys.argv[3])
