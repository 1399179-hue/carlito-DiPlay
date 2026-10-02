package com.shilapi.xcertplay

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import java.net.HttpURLConnection

/** The OS retries the saved outbox after loss of internet or a reboot. */
class SteeringProfileUploadService : JobService() {
    private class Run {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
    }
    private var active: Run? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run().also { active = it }
        Thread({
            var retry = false
            try {
                val pending = SteeringProfiles.outbox(this).listFiles().orEmpty()
                    .filter { it.extension == "json" }.sortedBy { it.lastModified() }
                for (file in pending) {
                    if (run.cancelled) break
                    if (!SteeringProfiles.upload(this, file) {
                        run.connection = it
                        if (run.cancelled) it.disconnect()
                    }) {
                        // Keep a refused submission, but let newer corrections reach cloud.
                        check(file.renameTo(java.io.File(file.parentFile, file.name + ".rejected")))
                    }
                }
            } catch (error: Exception) {
                retry = true
                Log.w("DiPlay-SteeringProfiles", "Cloud upload will retry", error)
            } finally {
                run.connection?.disconnect()
                if (!run.cancelled) jobFinished(params, retry)
            }
        }, "steering-profile-upload").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active?.let { it.cancelled = true; it.connection?.disconnect() }
        active = null
        return true
    }
}
