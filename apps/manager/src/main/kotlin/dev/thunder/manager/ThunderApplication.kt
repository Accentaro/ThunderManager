package dev.thunder.manager

import android.app.Application

class ThunderApplication : Application() {
    internal val managerController by lazy {
        RootlessManagerController(this, AndroidRootlessClonePipeline(this))
    }

    internal val signingIdentityRecoveryController by lazy {
        SigningIdentityRecoveryController(this)
    }

    internal val updateController by lazy {
        ManagerUpdateController(this)
    }
}
