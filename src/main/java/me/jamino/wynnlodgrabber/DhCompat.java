package me.jamino.wynnlodgrabber;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.enums.config.EDhApiServerFolderNameMode;

public class DhCompat {
    private boolean initialized = false;
    private final Runnable onInit;

    public DhCompat(Runnable onInit) {
        this.onInit = onInit;
    }

    public void registerEvents() {
        DhApiEventRegister.on(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
                Wynnlodgrabber.LOGGER.info("Distant Horizons initialized, setting folder mode to IP_ONLY");
                configure();
                initialized = true;
                onInit.run();
            }
        });
    }

    public void configure() {
        try {
            boolean success = DhApi.Delayed.configs.multiplayer().folderSavingMode()
                    .setValue(EDhApiServerFolderNameMode.IP_ONLY);
            if (success) {
                Wynnlodgrabber.LOGGER.info("Successfully set DH folder mode to IP_ONLY");
            } else {
                Wynnlodgrabber.LOGGER.warn("Failed to set DH folder mode to IP_ONLY, config may be locked");
            }
        } catch (Exception e) {
            Wynnlodgrabber.LOGGER.error("Error setting DH folder mode:", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getFolderMode() {
        return DhApi.Delayed.configs.multiplayer().folderSavingMode().getValue().toString();
    }

    public boolean setFolderModeIpOnly() {
        return DhApi.Delayed.configs.multiplayer().folderSavingMode()
                .setValue(EDhApiServerFolderNameMode.IP_ONLY);
    }
}
