package io.github.thebusybiscuit.slimefun4.core.services.github;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import io.github.thebusybiscuit.cscorelib2.players.MinecraftAccount;
import io.github.thebusybiscuit.cscorelib2.players.MinecraftAccount.TooManyRequestsException;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunPlugin;
import me.mrCookieSlime.Slimefun.api.Slimefun;

/**
 * This {@link GitHubTask} represents a {@link Runnable} that is run every X minutes.
 * It retrieves every {@link Contributor} of this project from GitHub.
 * 
 * @author TheBusyBiscuit
 * 
 * @see GitHubService
 * @see Contributor
 *
 */
class GitHubTask implements Runnable {

    private static final int MAX_REQUESTS_PER_MINUTE = 16;

    private final GitHubService gitHubService;

    GitHubTask(GitHubService github) {
        gitHubService = github;
    }

    @Override
    public void run() {
        gitHubService.getConnectors().forEach(GitHubConnector::pullFile);

        grabTextures();
    }

    private void grabTextures() {
        // Store all queried usernames to prevent 429 responses for pinging the
        // same URL twice in one run.
        Map<String, String> skins = new HashMap<>();
        int requests = 0;

        for (Contributor contributor : gitHubService.getContributors().values()) {
            int newRequests = requestTexture(contributor, skins);
            requests += newRequests;

            if (newRequests < 0 || requests >= MAX_REQUESTS_PER_MINUTE) {
                break;
            }
        }

        if (requests >= MAX_REQUESTS_PER_MINUTE && SlimefunPlugin.instance != null && SlimefunPlugin.instance.isEnabled()) {
            // Slow down API requests and wait a minute after more than x requests were made
            Bukkit.getScheduler().runTaskLaterAsynchronously(SlimefunPlugin.instance, this::grabTextures, 2 * 60 * 20L);
        }

        for (GitHubConnector connector : gitHubService.getConnectors()) {
            if (connector instanceof ContributionsConnector && !((ContributionsConnector) connector).hasFinished()) {
                return;
            }
        }

        // We only wanna save this if all Connectors finished already
        // This will run multiple times but thats okay, this way we get as much data as possible stored
        gitHubService.saveUUIDCache();
    }

    private int requestTexture(Contributor contributor, Map<String, String> skins) {
        if (!contributor.hasTexture()) {
            try {
                if (skins.containsKey(contributor.getMinecraftName())) {
                    contributor.setTexture(skins.get(contributor.getMinecraftName()));
                }
                else {
                    contributor.setTexture(pullTexture(skins, contributor));
                    return contributor.getUniqueId().isPresent() ? 1 : 2;
                }
            }
            catch (IllegalArgumentException x) {
                // There cannot be a texture found because it is not a valid MC username
                contributor.setTexture(null);
            }
            catch (IOException x) {
                // Too many requests
                Slimefun.getLogger().log(Level.WARNING, "Attempted to connect to mojang.com, got this response: {0}: {1}", new Object[] { x.getClass().getSimpleName(), x.getMessage() });
                Slimefun.getLogger().log(Level.WARNING, "This usually means mojang.com is down or started to rate-limit this connection, this is not an error message!");

                // Retry after 5 minutes if it was rate-limiting
                if (x.getMessage().contains("429")) {
                    Bukkit.getScheduler().runTaskLaterAsynchronously(SlimefunPlugin.instance, this::grabTextures, 5 * 60 * 20L);
                }

                return -1;
            }
            catch (TooManyRequestsException x) {
                Slimefun.getLogger().log(Level.WARNING, "Received a rate-limit from mojang.com, retrying in 4 minutes");
                Bukkit.getScheduler().runTaskLaterAsynchronously(SlimefunPlugin.instance, this::grabTextures, 4 * 60 * 20L);

                return -1;
            }
        }

        return 0;
    }

    private String pullTexture(Map<String, String> skins, Contributor contributor) throws TooManyRequestsException, IOException {
        Optional<UUID> uuid = contributor.getUniqueId();

        if (!uuid.isPresent()) {
            uuid = MinecraftAccount.getUUID(contributor.getMinecraftName());

            if (uuid.isPresent()) {
                contributor.setUniqueId(uuid.get());
            }
        }

        if (uuid.isPresent()) {
            Optional<String> skin = MinecraftAccount.getSkin(uuid.get());
            skins.put(contributor.getMinecraftName(), skin.orElse(""));
            return skin.orElse(null);
        }
        else {
            return null;
        }
    }

}
