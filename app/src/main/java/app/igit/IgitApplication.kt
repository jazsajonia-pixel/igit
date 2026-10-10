package app.igit

import android.app.Application
import app.igit.sync.Notifier
import org.eclipse.jgit.util.FS

class IgitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // JGit looks for ~/.gitconfig and ~/.ssh; point "home" at private app storage on Android.
        FS.DETECTED.setUserHome(filesDir)
        Notifier.createChannels(this)
    }
}
