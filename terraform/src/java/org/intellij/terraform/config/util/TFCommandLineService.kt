// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform.config.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.headTailOrNull
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

interface TFCommandLineService {

  fun wrapCommandLine(commandLine: GeneralCommandLine): GeneralCommandLine

}

class TFCommandLineServiceImpl : TFCommandLineService {
  override fun wrapCommandLine(commandLine: GeneralCommandLine): GeneralCommandLine = commandLine
}

class TFCommandLineServiceMock : TFCommandLineService {

  private val mocks = ConcurrentHashMap<String, Process>()


  fun mockCommandLine(commandLine: String, stdout: String, disposable: Disposable): Unit =
    mockCommandLine(commandLine, stdout, 0, disposable)

  fun mockCommandLine(commandLine: String, stdout: String, exitCode: Int, disposable: Disposable) {
    mocks.put(commandLine, object : Process() {
      override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

      override fun getInputStream(): InputStream = stdout.byteInputStream()

      override fun getErrorStream(): InputStream = InputStream.nullInputStream()

      override fun waitFor(): Int = exitCode

      override fun exitValue(): Int = exitCode

      override fun destroy() {}
    })
    Disposer.register(disposable) { mocks.remove(commandLine) }
  }

  private val errors = ContainerUtil.createConcurrentList<Throwable>()

  private fun normalizedCmd(commandLine: GeneralCommandLine): String {
    val commandLineString = commandLine.commandLineString
    val separator = commandLine.exePath.lastIndexOf(File.separatorChar)
    if (separator == -1) return commandLineString
    return commandLineString.substring(separator + 1)
  }

  override fun wrapCommandLine(commandLine: GeneralCommandLine): GeneralCommandLine = object : GeneralCommandLine() {
    override fun createProcess(): Process {
      val commandLineString = normalizedCmd(commandLine)
      return mocks[commandLineString] ?: throw AssertionError("Missing mock for $commandLineString").also { errors.add(it) }
    }
  }

  fun throwErrorsIfAny() {
    val (head, tail) = errors.toList().headTailOrNull() ?: return
    errors.clear()
    for (throwable in tail) {
      head.addSuppressed(throwable)
    }
    throw head
  }

  companion object {

    @get:TestOnly
    val instance: TFCommandLineServiceMock
      get() = (service<TFCommandLineService>() as TFCommandLineServiceMock)

  }

}