// Interruptible reads from a caller-owned file descriptor (roadmap 5.3).
#pragma once

#include <cstdint>
#include <memory>

namespace remux {

struct Task;

// Reads a descriptor on a helper thread, so that a read stuck in its provider (FUSE, a
// ProxyFileDescriptor callback, a cloud provider) can be given up on when the task is cancelled or the
// read stalls for longer than the task's read timeout. A blocked read() cannot be interrupted from
// another thread (FUSE waits for its daemon even after a signal), so the helper reads from its own
// dup of the descriptor with pread into its own buffer: giving up never touches the caller's
// descriptor or FFmpeg's buffer, and the helper closes the dup and frees its state once its last
// read returns. Used as an AVIOContext's opaque with Read/Seek.
class FdReader {
public:
    // Null with errno set if the descriptor cannot be duplicated or the thread not started.
    static std::unique_ptr<FdReader> Open(int fd, const Task* task);
    ~FdReader();

    FdReader(const FdReader&) = delete;
    FdReader& operator=(const FdReader&) = delete;

    // AVIOContext callbacks; `opaque` is the FdReader.
    static int Read(void* opaque, uint8_t* buffer, int size);
    static int64_t Seek(void* opaque, int64_t offset, int whence);

private:
    struct Shared;

    FdReader(std::shared_ptr<Shared> shared, const Task* task) : shared_(std::move(shared)), task_(task) {}

    // The helper thread: answers read requests until stopped.
    static void Serve(const std::shared_ptr<Shared>& shared);

    int ReadAt(uint8_t* buffer, int size);

    const std::shared_ptr<Shared> shared_;
    const Task* const task_;
    int64_t position_ = 0;
    // Nonzero once a read was given up on (the helper may still be inside it): AVERROR_EXIT or
    // AVERROR(ETIMEDOUT), returned by every later read, so a retry reports the same failure.
    int abandoned_ = 0;
};

}  // namespace remux
