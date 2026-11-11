package app.transittracker.core

/** Creates the real [app.transittracker.model.TransitCore]. */
object TransitCoreFactory {
    /**
     * Builds a core from platform [deps]. Nothing runs until
     * [app.transittracker.model.TransitCore.start] is called; cancel [CoreDependencies.scope] to stop it.
     */
    fun create(config: CoreConfig, deps: CoreDependencies): DefaultTransitCore = DefaultTransitCore(config, deps)
}
