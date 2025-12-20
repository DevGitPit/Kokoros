#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>

// Define the opaque struct
struct audio_object {
    int dummy;
};

// Implementations

int audio_object_open(struct audio_object *object,
                  int format,
                  uint32_t rate,
                  uint8_t channels) {
    return 0; // Success
}

void audio_object_close(struct audio_object *object) {
    // No-op
}

void audio_object_destroy(struct audio_object *object) {
    if (object) free(object);
}

int audio_object_write(struct audio_object *object,
                   const void *data,
                   size_t bytes) {
    return 0; // Success, claimed to write 0 bytes? Or maybe bytes? 
              // Usually returns 0 on success in this lib? 
              // Checking header: returns int (error code usually). 
              // Standard C returns 0 for success usually.
    return 0;
}

int audio_object_drain(struct audio_object *object) {
    return 0;
}

int audio_object_flush(struct audio_object *object) {
    return 0;
}

const char * audio_object_strerror(struct audio_object *object, int error) {
    return "Success";
}

struct audio_object * create_audio_device_object(const char *device,
                           const char *application_name,
                           const char *description) {
    struct audio_object *obj = (struct audio_object *)malloc(sizeof(struct audio_object));
    return obj;
}
