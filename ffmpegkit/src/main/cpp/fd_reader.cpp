#include "fd_reader.h"

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

#include "remux.h"

namespace remux {

namespace {

// How often a waiting read checks for cancellation.
constexpr auto kPollInterval = std::chrono::milliseconds(20);

}  // namespace

// State shared by the reading thread and the helper; whichever leaves last frees it.
struct FdReader::Shared {
    std::mutex mutex;
    std::condition_variable changed;
    int fd = -1;  // the dup; owned and closed by the helper
    bool stop = false;
    bool requested = false;
    int64_t offset = 0;
    int size = 0;
    bool answered = false;
    ssize_t result = 0;
    int error = 0;
    std::vector<uint8_t> buffer;  // written by the helper between a request and its answer
};

std::unique_ptr<FdReader> FdReader::Open(int fd, const Task* task) {
    auto shared = std::make_shared<Shared>();
    shared->fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (shared->fd < 0) return nullptr;
    try {
        std::thread(Serve, shared).detach();
    } catch (const std::system_error& e) {
        close(shared->fd);
        errno = e.code().value();
        return nullptr;
    }
    return std::unique_ptr<FdReader>(new FdReader(shared, task));
}

FdReader::~FdReader() {
    std::lock_guard<std::mutex> lock(shared_->mutex);
    shared_->stop = true;  // the helper exits now, or after the read it is stuck in
    shared_->changed.notify_all();
}

int FdReader::Read(void* opaque, uint8_t* buffer, int size) {
    return static_cast<FdReader*>(opaque)->ReadAt(buffer, size);
}

int64_t FdReader::Seek(void* opaque, int64_t offset, int whence) {
    auto* reader = static_cast<FdReader*>(opaque);
    if (whence == AVSEEK_SIZE || (whence & ~AVSEEK_FORCE) == SEEK_END) {
        struct stat st {};
        if (fstat(reader->shared_->fd, &st) != 0 || !S_ISREG(st.st_mode)) return AVERROR(ENOSYS);
        if (whence == AVSEEK_SIZE) return st.st_size;
        offset += st.st_size;
    } else if ((whence & ~AVSEEK_FORCE) == SEEK_CUR) {
        offset += reader->position_;
    } else if ((whence & ~AVSEEK_FORCE) != SEEK_SET) {
        return AVERROR(EINVAL);
    }
    if (offset < 0) return AVERROR(EINVAL);
    reader->position_ = offset;
    return offset;
}

int FdReader::ReadAt(uint8_t* buffer, int size) {
    if (abandoned_ != 0) return abandoned_;
    if (task_->IsCancelled()) return AVERROR_EXIT;
    std::unique_lock<std::mutex> lock(shared_->mutex);
    shared_->buffer.resize(static_cast<size_t>(size));
    shared_->offset = position_;
    shared_->size = size;
    shared_->answered = false;
    shared_->requested = true;
    shared_->changed.notify_all();

    const int64_t timeout_us = task_->read_timeout_us.load(std::memory_order_relaxed);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::microseconds(timeout_us);
    while (!shared_->answered) {
        shared_->changed.wait_for(lock, kPollInterval);
        if (shared_->answered) break;
        if (task_->IsCancelled()) {
            abandoned_ = AVERROR_EXIT;
            return abandoned_;
        }
        if (timeout_us > 0 && std::chrono::steady_clock::now() >= deadline) {
            abandoned_ = AVERROR(ETIMEDOUT);
            return abandoned_;
        }
    }
    if (shared_->result < 0) return AVERROR(shared_->error);
    if (shared_->result == 0) return AVERROR_EOF;
    std::memcpy(buffer, shared_->buffer.data(), static_cast<size_t>(shared_->result));
    position_ += shared_->result;
    return static_cast<int>(shared_->result);
}

void FdReader::Serve(const std::shared_ptr<Shared>& shared) {
    std::unique_lock<std::mutex> lock(shared->mutex);
    while (true) {
        shared->changed.wait(lock, [&] { return shared->requested || shared->stop; });
        if (shared->stop) break;
        shared->requested = false;
        const int64_t offset = shared->offset;
        const int size = shared->size;
        uint8_t* data = shared->buffer.data();  // not resized until this read is answered
        lock.unlock();
        ssize_t n;
        do {
            n = pread(shared->fd, data, static_cast<size_t>(size), offset);
        } while (n < 0 && errno == EINTR);
        const int error = errno;
        lock.lock();
        shared->result = n;
        shared->error = error;
        shared->answered = true;
        shared->changed.notify_all();
    }
    close(shared->fd);
}

}  // namespace remux
