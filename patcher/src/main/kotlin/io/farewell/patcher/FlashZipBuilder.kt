package io.farewell.patcher

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FlashZipBuilder {

    fun build(output: File, template: Map<String, ByteArray>, files: Map<String, File>) {
        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            for ((name, bytes) in template) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((name, file) in files) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun entriesForFramework(framework: File, services: File?): Map<String, File> {
        val map = LinkedHashMap<String, File>()
        map["system_root/system/framework/framework.jar"] = framework
        if (services != null) {
            map["system_root/system/framework/services.jar"] = services
        }
        return map
    }
}
