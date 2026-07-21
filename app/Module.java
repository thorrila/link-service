import com.google.inject.AbstractModule;
import services.ClickDrainService;

/**
 * Play auto-loads a class named "Module" in the root package, no application.conf entry needed.
 * Forces ClickDrainService to be built at boot, otherwise nothing would inject it until the
 * first click, and its background drain loop would never start.
 */
public class Module extends AbstractModule {

    @Override
    protected void configure() {
        bind(ClickDrainService.class).asEagerSingleton();
    }
}
