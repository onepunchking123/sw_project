package com.example.careguardcamera.inference

import ai.onnxruntime.OrtSession

object OnnxSessionOptions {
    private const val INTRA_OP_THREADS = 2
    private const val INTER_OP_THREADS = 1

    fun create(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setIntraOpNumThreads(INTRA_OP_THREADS)
            setInterOpNumThreads(INTER_OP_THREADS)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
        }
    }
}
