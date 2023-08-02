package com.intellij.dts.util

import com.intellij.dts.settings.DtsSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.*
import java.io.File

object DtsZephyrUtil {
    private val boardsPath = "boards"
    private val bindingsPath = "dts/bindings"

    private val includePaths = listOf(
        "include",
        "dts",
        "dts/common",
    )

    private fun searchRootDir(project: Project): VirtualFile? {
        val candidates = mutableListOf<VirtualFile>()

        val visitor = object : VirtualFileVisitor<Any>(limit(2)) {
            override fun visitFile(file: VirtualFile): Boolean {
                if (!file.isDirectory) return true

                // check if directories exist
                for (dirPath in listOf(boardsPath, bindingsPath) + includePaths) {
                    val dir = file.findDirectory(dirPath)
                    if (dir == null || !dir.exists() || dir.children.isEmpty()) return true
                }

                candidates.add(file)

                return true
            }
        }

        for (module in project.modules) {
            val rootManger = ModuleRootManager.getInstance(module)

            for (root in rootManger.contentRoots) {
                VfsUtilCore.visitChildrenRecursively(root, visitor)
            }
        }

        // TODO: search for best match zephyr directory

        return candidates.firstOrNull()
    }

    private fun getRootDir(project: Project, settings: DtsSettings): VirtualFile? {
        val path = settings.state.zephyrRoot

        return if (path.isEmpty()) {
            val result = searchRootDir(project) ?: return null
            settings.update { zephyrRoot = result.path }

            result
        } else {
            LocalFileSystem.getInstance().findFileByIoFile(File(path))
        }
    }

    private fun getSubDir(project: Project, settings: DtsSettings, relativePath: String): VirtualFile? {
        val zephyr = getRootDir(project, settings) ?: return null
        return zephyr.findDirectory(relativePath)
    }

    fun getBoardDir(project: Project): VirtualFile? {
        val settings = DtsSettings.of(project)

        val board = settings.zephyrBoard ?: return null
        val arch = settings.zephyrArch ?: return null

        return getSubDir(project, settings, "$boardsPath/$arch/$board")
    }

    fun getIncludeDirs(project: Project): List<VirtualFile> {
        val settings = DtsSettings.of(project)

        val includes = sequence {
            yieldAll(includePaths)
            settings.zephyrArch?.let { yield("dts/$it") }
        }

        return includes.mapNotNull { getSubDir(project, settings, it) }.toList()
    }

    fun getBindingsDir(project: Project): VirtualFile? {
        val settings = DtsSettings.of(project)
        return getSubDir(project, settings, bindingsPath)
    }
}