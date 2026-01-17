
package com.gillodaby.fullbright;

import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public class FullbrightPlugin extends JavaPlugin {

    public FullbrightPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
    }

    @Override
    public void start() {
        Fullbright.init();
        System.out.println("[Fullbright] Started: torch light boost enabled.");
    }
}
