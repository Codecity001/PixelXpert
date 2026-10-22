package sh.siava.pixelxpert.di

import org.koin.dsl.module
import sh.siava.pixelxpert.data.PrefStore

/**
 * Application-wide singletons.
 *
 * [PrefStore] is the only object that touches the preference file, so keeping
 * it a Koin singleton guarantees every screen reads and writes the same
 * `SharedPreferences` instance (and therefore the same revision stream).
 */
val pxnModule = module {
	single { PrefStore(get()) }
}
