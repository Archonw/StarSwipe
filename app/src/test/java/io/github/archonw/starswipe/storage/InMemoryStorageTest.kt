package io.github.archonw.starswipe.storage

class InMemoryStorageTest : PhotoStorageContract() {
    override fun create(files: Map<String, ByteArray>): PhotoStorage = InMemoryStorage(files)
}
