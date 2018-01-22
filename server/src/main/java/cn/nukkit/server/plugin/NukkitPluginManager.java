package cn.nukkit.server.plugin;

import cn.nukkit.api.permission.Permission;
import cn.nukkit.api.plugin.Plugin;
import cn.nukkit.api.plugin.PluginDescription;
import cn.nukkit.api.plugin.PluginLoader;
import cn.nukkit.api.plugin.PluginManager;
import cn.nukkit.server.NukkitServer;
import cn.nukkit.server.math.graph.DirectedAcyclicGraph;
import cn.nukkit.server.permission.NukkitPermission;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import lombok.extern.log4j.Log4j2;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author MagicDroidX
 */
@Log4j2
public class NukkitPluginManager implements PluginManager {
    private final BiMap<Class<? extends PluginLoader>, PluginLoader> loaders = HashBiMap.create();
    private final Map<String, Plugin> plugins = new HashMap<>();
    private final NukkitServer server;
    private final Pattern API_REGEX;
    private final int API_MINOR;

    public NukkitPluginManager(NukkitServer server) {
        this.server = server;
        String[] api = NukkitServer.API_VERSION.split("\\.");
        API_MINOR = Integer.parseInt(api[1]);
        API_REGEX = Pattern.compile("(?:v)(" + api[0] +")\\.([0-9])\\.([0-9])");
    }

    public <T extends PluginLoader> void registerPluginLoader(Class<T> loaderClass, T loaderInstance) {
        Preconditions.checkNotNull(loaderClass, "loaderClass");
        Preconditions.checkNotNull(loaderInstance, "loaderInstance");
        if (loaders.containsKey(loaderClass)) {
            throw new IllegalArgumentException("The plugin loader has already been registered.");
        }
        loaders.put(loaderClass, loaderInstance);
    }

    public <T extends PluginLoader> void unregisterPluginLoader(Class<T> loaderClass) {
        Preconditions.checkNotNull(loaderClass, "loaderClass");
        if (!loaders.containsKey(loaderClass)) {
            throw new IllegalArgumentException("The plugin loader was never registered.");
        }
        loaders.remove(loaderClass);
    }

    public Optional<Plugin> getPlugin(String name) {
        Preconditions.checkNotNull(name, "name");
        return Optional.ofNullable(plugins.get(name));
    }

    public Collection<Plugin> getAllPlugins() {
        return ImmutableList.copyOf(plugins.values());
    }

    public void loadPlugins(Path pluginDirectory) throws Exception {
        Preconditions.checkNotNull(pluginDirectory, "pluginDirectory");
        loadPlugins(pluginDirectory, null);
    }

    public void loadPlugins(Path pluginDirectory, Collection<Class<? extends PluginLoader>> loaderClasses) throws Exception {
        Preconditions.checkNotNull(pluginDirectory, "directory");
        Preconditions.checkArgument(Files.isDirectory(pluginDirectory), "provided path isn't a directory");
        Collection<PluginLoader> pluginLoaders = getPluginLoaders(loaderClasses);
        List<PluginDescription> found = new ArrayList<>();
        Map<PluginDescription, PluginLoader> foundPluginMap = new HashMap<>();
        for (PluginLoader loader: pluginLoaders) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDirectory, p -> Files.isRegularFile(p) && p.toString().endsWith(loader.getPluginFileExtension()))) {
                for (Path path : stream) {
                    try {
                        PluginDescription des = loader.loadPlugin(path);
                        found.add(des);
                        foundPluginMap.put(des, loader);
                    } catch (Exception e) {
                        log.error("Unable to enumerate plugin {}", path, e);
                    }
                }
            }
        }

        if (found.isEmpty()) {
            // No plugins found.
            return;
        }

        List<PluginDescription> sortedPlugins = sortDescriptions(found);
        // Now load the plugins.
        pluginLoad: for (PluginDescription plugin : sortedPlugins) {
            // Verify API version is compatible.
            if (incompatibleApiVersion(plugin.getApiVersions())) {
                log.error(server.getLanguage().translateString("nukkit.plugin.loadError", plugin.getName(), "Wrong API format"));
                continue;
            }

            if (plugins.containsKey(plugin.getName())) {
                server.getLanguage().translateString("nukkit.plugin.duplicateError", plugin.getName());
                continue;
            }

            // Verify dependencies first.
            for (String s : plugin.getDependencies()) {
                Optional<Plugin> loadedPlugin = getPlugin(s);
                if (!loadedPlugin.isPresent()) {
                    log.error("Can't load plugin {} due to missing dependency {}", plugin.getName(), s);
                    continue pluginLoad;
                }
            }

            // Now actually create the plugin.
            Plugin pluginObject;
            try {
                pluginObject = foundPluginMap.get(plugin).createPlugin(plugin);
            } catch (Exception e) {
                log.error("Can't create plugin {}", plugin.getName(), e);
                continue;
            }

            plugins.put(plugin.getName(), pluginObject);
        }
    }

    @Nonnull
    private Collection<PluginLoader> getPluginLoaders(@Nullable Collection<Class<? extends PluginLoader>> loaderClasses) {
        final Collection<PluginLoader> pluginLoaders = new ArrayList<>();
        if (loaderClasses == null) {
            pluginLoaders.addAll(loaders.values());
        } else {
            loaderClasses.forEach(loaderClass -> {
                if (loaders.containsKey(loaderClass)) {
                    pluginLoaders.add(loaders.get(loaderClass));
                }
            });
        }
        return pluginLoaders;
    }

    private boolean incompatibleApiVersion(Collection<String> apiVersions) {
        try {
            for (String apiVersion : apiVersions) {
                Matcher apiMatcher = API_REGEX.matcher(apiVersion);
                if (apiMatcher.matches()) {
                    if (Integer.parseInt(apiMatcher.group(1)) < API_MINOR) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    public boolean isPluginEnabled(Plugin plugin) {
        if (plugin != null && this.plugins.containsKey(plugin.getDescription().getName())) {
            return plugin.isEnabled();
        } else {
            return false;
        }
    }

    public void enablePlugin(Plugin plugin) {
        if (!plugin.isEnabled()) {
            try {
                for (NukkitPermission nukkitPermission : plugin.getDescription().getPermissions()) {
                    server.getPermissionManager().addPermission(nukkitPermission);
                }
                plugin.getPluginLoader().enablePlugin(plugin);
            } catch (Throwable e) {
                log.throwing(e);
                disablePlugin(plugin);
            }
        }
    }

    public void disablePlugins() {
        plugins.values().forEach(this::disablePlugin);
    }

    public void disablePlugin(Plugin plugin) {
        if (plugin.isEnabled()) {
            try {
                plugin.getPluginLoader().disablePlugin(plugin);
            } catch (Exception e) {
                log.throwing(e);
            }

            server.getScheduler().cancelTasks(object);
            server.getEventManager().unregisterAllListeners(object);
            for (Permission nukkitPermission : plugin.getPermissions()) {
                server.getPermissionManager().removePermission(nukkitPermission);
            }
        }
    }

    public void clearPlugins() {
        this.disablePlugins();
        this.plugins.clear();
    }

    @VisibleForTesting
    private List<PluginDescription> sortDescriptions(List<PluginDescription> descriptions) {
        // Create the graph.
        DirectedAcyclicGraph<PluginDescription> graph = new DirectedAcyclicGraph<>();

        // Add edges
        for (PluginDescription description : descriptions) {
            graph.add(description);
            // Dependencies
            for (String s : description.getDependencies()) {
                Optional<PluginDescription> in = descriptions.stream().filter(d -> d.getName().equals(s)).findFirst();
                in.ifPresent(pluginDescription -> graph.addEdges(description, pluginDescription));
            }

            // Plugins to load before
            for (String s : description.getPluginsToLoadBefore()) {
                Optional<PluginDescription> in = descriptions.stream().filter(d -> d.getName().equals(s)).findFirst();
                in.ifPresent(pluginDescription -> graph.addEdges(pluginDescription, description)); // Load ordering is opposite to dependency loading.
            }
        }

        return DirectedAcyclicGraph.sort(graph);
    }
}