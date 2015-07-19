package act.app;

import act.Act;
import act.app.data.BinderManager;
import act.app.data.StringValueResolverManager;
import act.app.event.AppEventId;
import act.conf.AppConfLoader;
import act.conf.AppConfig;
import act.controller.ControllerSourceCodeScanner;
import act.controller.bytecode.ControllerByteCodeScanner;
import act.di.DependencyInjector;
import act.event.EventBus;
import act.handler.builtin.StaticFileGetter;
import act.job.AppJobManager;
import act.job.meta.JobByteCodeScanner;
import act.job.meta.JobSourceCodeScanner;
import act.mail.MailerConfigManager;
import act.mail.MailerSourceCodeScanner;
import act.mail.bytecode.MailerByteCodeScanner;
import act.route.RouteTableRouterBuilder;
import act.route.Router;
import act.util.ClassInfoByteCodeScanner;
import act.util.ClassInfoSourceCodeScanner;
import act.util.UploadFileStorageService;
import act.view.ActServerError;
import org.apache.commons.codec.Charsets;
import org.osgl._;
import org.osgl.exception.UnexpectedException;
import org.osgl.http.H;
import org.osgl.logging.L;
import org.osgl.logging.Logger;
import org.osgl.storage.IStorageService;
import org.osgl.util.*;

import java.io.File;
import java.security.InvalidKeyException;
import java.util.List;

import static act.app.event.AppEventId.*;

/**
 * {@code App} represents an application that is deployed in a Act container
 */
public class App {

    private static Logger logger = L.get(App.class);

    private static App INST;

    public enum F {
        ;
        public static _.Predicate<String> JAVA_SOURCE = S.F.endsWith(".java");
        public static _.Predicate<String> JAR_FILE = S.F.endsWith(".jar");
        public static _.Predicate<String> CONF_FILE = S.F.endsWith(".conf").or(S.F.endsWith(".properties"));
        public static _.Predicate<String> ROUTES_FILE = _.F.eq(RouteTableRouterBuilder.ROUTES_FILE);
    }

    private File appBase;
    private File appHome;
    private Router router;
    private AppConfig config;
    private AppClassLoader classLoader;
    private ProjectLayout layout;
    private AppBuilder builder;
    private EventBus eventBus;
    private AppCodeScannerManager scannerManager;
    private DbServiceManager dbServiceManager;
    private AppJobManager jobManager;
    private MailerConfigManager mailerConfigManager;
    private StringValueResolverManager resolverManager;
    private BinderManager binderManager;
    private AppInterceptorManager interceptorManager;
    private DependencyInjector<?> dependencyInjector;
    private IStorageService uploadFileStorageService;
    private ServiceResourceManager serviceResourceManager;
    // used in dev mode only
    private CompilationException compilationException;

    protected App() {
        INST = this;
    }

    protected App(File appBase, ProjectLayout layout) {
        this.appBase = appBase;
        this.layout = layout;
        this.appHome = RuntimeDirs.home(this);
        INST = this;
    }

    public static App instance() {
        return INST;
    }

    public AppConfig config() {
        return config;
    }

    public Router router() {
        return router;
    }

    /**
     * The base dir where an application sit within
     */
    public File base() {
        return appBase;
    }

    /**
     * The home dir of an application, referenced only
     * at runtime.
     * <p><b>Note</b> when app is running in dev mode, {@code appHome}
     * shall be {@code appBase/target}, while app is deployed to
     * Act at other mode, {@code appHome} shall be the same as
     * {@code appBase}</p>
     */
    public File home() {
        return appHome;
    }

    public AppClassLoader classLoader() {
        return classLoader;
    }

    public ProjectLayout layout() {
        return layout;
    }

    public void detectChanges() {
        classLoader.detectChanges();
        if (null != compilationException) {
            throw new ActServerError(compilationException, this);
        }
    }

    public void restart() {
        build();
        refresh();
    }

    public void refresh() {
        Act.viewManager().reload(this);
        initServiceResourceManager();
        initEventBus();
        initInterceptorManager();
        loadConfig();
        initJobManager();
        initMailerConfigManager();
        initResolverManager();
        initBinderManager();
        initUploadFileStorageService();
        initRouter();
        initDbServiceManager();
        eventBus().emit(DB_SVC_LOADED);
        loadGlobalPlugin();
        initScannerManager();
        loadActScanners();
        loadBuiltInScanners();
        eventBus().emit(PRE_LOAD_CLASSES);
        initClassLoader();
        try {
            scanAppCodes();
            compilationException = null;
        } catch (CompilationException e) {
            compilationException = e;
            throw new ActServerError(e, this);
        }
        eventBus().emit(APP_CODE_SCANNED);
        loadRoutes();
        // setting context class loader here might lead to memory leaks
        // and cause weird problems as class loader been set to thread
        // could be switched to handling other app in ACT or still hold
        // old app class loader instance after the app been refreshed
        // - Thread.currentThread().setContextClassLoader(classLoader());
        eventBus().emit(PRE_START);
        eventBus().emit(START);
    }

    public AppBuilder builder() {
        return builder;
    }

    void build() {
        builder = AppBuilder.build(this);
    }

    void hook() {
        Act.hook(this);
    }

    public File tmpDir() {
        return new File(this.layout().target(appBase), "tmp");
    }

    public File file(String path) {
        return new File(home(), path);
    }

    public AppInterceptorManager interceptorManager() {
        return interceptorManager;
    }

    public AppCodeScannerManager scannerManager() {
        return scannerManager;
    }

    public DbServiceManager dbServiceManager() {
        return dbServiceManager;
    }

    public StringValueResolverManager resolverManager() {
        return resolverManager;
    }

    public BinderManager binderManager() {return binderManager;}

    public MailerConfigManager mailerConfigManager() {
        return mailerConfigManager;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public AppJobManager jobManager() {
        return jobManager;
    }

    public App injector(DependencyInjector<?> dependencyInjector) {
        E.NPE(dependencyInjector);
        E.illegalStateIf(null != this.dependencyInjector, "Dependency injection factory already set");
        this.dependencyInjector = dependencyInjector;
        return this;
    }

    public <T extends DependencyInjector<T>> T injector() {
        return (T) dependencyInjector;
    }

    public IStorageService uploadFileStorageService() {
        return uploadFileStorageService;
    }

    public String sign(String message) {
        return Crypto.sign(message, config().secret().getBytes(Charsets.UTF_8));
    }

    public String encrypt(String message) {
        try {
            return Crypto.encryptAES(message, config().secret());
        } catch (UnexpectedException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvalidKeyException) {
                logger.error("Cannot encrypt/decrypt! Please download Java Crypto Extension pack from Oracle: http://www.oracle.com/technetwork/java/javase/tech/index-jsp-136007.html");
                if (Act.isDev()) {
                    logger.warn("Application will keep running with no encrypt/decrypt facilities in Dev mode");
                    return Codec.encodeBASE64(message);
                }
            }
            throw e;
        }
    }

    public String decrypt(String message) {
        try {
            return Crypto.decryptAES(message, config().secret());
        } catch (UnexpectedException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvalidKeyException) {
                logger.error("Cannot encrypt/decrypt! Please download Java Crypto Extension pack from Oracle: http://www.oracle.com/technetwork/java/javase/tech/index-jsp-136007.html");
                if (Act.isDev()) {
                    logger.warn("Application will keep running with no encrypt/decrypt facilities in Dev mode");
                    return new String(Codec.decodeBASE64(message));
                }
            }
            throw e;
        }
    }

    public <T> T newInstance(Class<T> clz) {
        if (App.class == clz) return _.cast(this);
        if (AppConfig.class == clz) return _.cast(config());
        if (null != dependencyInjector) {
            return dependencyInjector.create(clz);
        } else {
            return _.newInstance(clz);
        }
    }

    <T> T newInstance(Class<T> clz, ActionContext context) {
        if (App.class == clz) return _.cast(this);
        if (AppConfig.class == clz) return _.cast(config());
        if (ActionContext.class == clz) return _.cast(context);
        if (null != dependencyInjector) {
            return dependencyInjector.createContextAwareInjector(context).create(clz);
        } else {
            return _.newInstance(clz);
        }
    }

    @Override
    public int hashCode() {
        return appBase.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof App) {
            App that = (App) obj;
            return _.eq(that.appBase, appBase);
        }
        return false;
    }

    @Override
    public String toString() {
        return S.builder("app@[").append(appBase).append("]").toString();
    }

    App register(AppService service) {
        serviceResourceManager.register(service);
        return this;
    }

    private void loadConfig() {
        File conf = RuntimeDirs.conf(this);
        logger.debug("loading app configuration: %s ...", appBase.getPath());
        config = new AppConfLoader().load(conf);
        config.app(this);
    }

    private void initServiceResourceManager() {
        if (null != serviceResourceManager) {
            eventBus().emit(STOP);
            serviceResourceManager.destroy();
            dependencyInjector = null;
        }
        serviceResourceManager = new ServiceResourceManager();
    }

    private void initUploadFileStorageService() {
        uploadFileStorageService = UploadFileStorageService.create(this);
    }

    private void initRouter() {
        router = new Router(this);
    }

    private void initEventBus() {
        eventBus = new EventBus(this);
    }

    private void initJobManager() {
        jobManager = new AppJobManager(this);
    }

    private void initInterceptorManager() {
        interceptorManager = new AppInterceptorManager(this);
    }

    private void initScannerManager() {
        scannerManager = new AppCodeScannerManager(this);
    }

    private void initDbServiceManager() {
        dbServiceManager = new DbServiceManager(this);
    }

    private void initMailerConfigManager() {
        mailerConfigManager = new MailerConfigManager(this);
    }

    private void loadGlobalPlugin() {
        Act.appServicePluginManager().applyTo(this);
    }

    private void loadActScanners() {
        Act.scannerPluginManager().initApp(this);
    }

    private void loadBuiltInScanners() {
        scannerManager.register(new ClassInfoSourceCodeScanner());
        scannerManager.register(new ClassInfoByteCodeScanner());
        scannerManager.register(new ControllerSourceCodeScanner());
        scannerManager.register(new ControllerByteCodeScanner());
        scannerManager.register(new MailerSourceCodeScanner());
        scannerManager.register(new MailerByteCodeScanner());
        scannerManager.register(new JobSourceCodeScanner());
        scannerManager.register(new JobByteCodeScanner());
    }

    private void loadRoutes() {
        loadBuiltInRoutes();
        logger.debug("loading app routing table: %s ...", appBase.getPath());
        File routes = RuntimeDirs.routes(this);
        if (!(routes.isFile() && routes.canRead())) {
            logger.warn("Cannot find routeTable file: %s", appBase.getPath());
            // guess the app is purely using annotation based routes
            return;
        }
        List<String> lines = IO.readLines(routes);
        new RouteTableRouterBuilder(lines).build(router);
    }

    private void loadBuiltInRoutes() {
        router().addMapping(H.Method.GET, "/asset/", new StaticFileGetter(layout().asset(base())));
    }

    private void initClassLoader() {
        classLoader = Act.mode().classLoader(this);
        eventBus().emit(AppEventId.CLASS_LOADER_INITIALIZED);
        classLoader.preload();
        eventBus().emit(AppEventId.CLASS_LOADED);
    }

    private void initResolverManager() {
        resolverManager = new StringValueResolverManager(this);
    }
    private void initBinderManager() {
        binderManager = new BinderManager(this);
    }

    private void loadPlugins() {
        // TODO: load app level plugins
    }

    private void scanAppCodes() {
        classLoader().scan();
        //classLoader().scan();
    }

    static App create(File appBase, ProjectLayout layout) {
        return new App(appBase, layout);
    }


}