package de.jotomo.ruffyscripter;

import android.os.RemoteException;
import android.os.SystemClock;

import org.monkey.d.ruffy.ruffy.driver.IRTHandler;
import org.monkey.d.ruffy.ruffy.driver.IRuffyService;
import org.monkey.d.ruffy.ruffy.driver.display.Menu;
import org.monkey.d.ruffy.ruffy.driver.display.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jotomo.ruffyscripter.commands.Command;
import de.jotomo.ruffyscripter.commands.CommandException;
import de.jotomo.ruffyscripter.commands.CommandResult;

// TODO regularly read "My data" history (boluses, TBR) to double check all commands ran successfully.
// Automatically compare against AAPS db, or log all requests in the PumpInterface (maybe Milos
// already logs those requests somewhere ... and verify they have all been ack'd by the pump properly

/**
 * provides scripting 'runtime' and operations. consider moving operations into a separate
 * class and inject that into executing commands, so that commands operately solely on
 * operations and are cleanly separated from the thread management, connection management etc
 */
public class RuffyScripter {
    private static final Logger log = LoggerFactory.getLogger(RuffyScripter.class);

    public volatile Menu currentMenu;

    private final IRuffyService ruffyService;
    private volatile CommandResult cmdResult;
    private volatile long menuLastUpdated = 0;

    public RuffyScripter(IRuffyService ruffyService) {
        this.ruffyService = ruffyService;
        try {
            ruffyService.setHandler(mHandler);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private IRTHandler mHandler = new IRTHandler.Stub() {
        @Override
        public void log(String message) throws RemoteException {
            log.trace(message);
        }

        @Override
        public void fail(String message) throws RemoteException {
            log.warn(message);
        }

        @Override
        public void requestBluetooth() throws RemoteException {
            log.trace("Ruffy invoked requestBluetooth callback");
        }

        @Override
        public void rtStopped() throws RemoteException {
            log.debug("rtStopped callback invoked");
            currentMenu = null;
        }

        @Override
        public void rtStarted() throws RemoteException {
            log.debug("rtStarted callback invoked");
        }

        @Override
        public void rtClearDisplay() throws RemoteException {
        }

        @Override
        public void rtUpdateDisplay(byte[] quarter, int which) throws RemoteException {
        }

        @Override
        public void rtDisplayHandleMenu(Menu menu) throws RemoteException {
            // method is called every ~500ms
            log.debug("rtDisplayHandleMenu: " + menu.getType());

            currentMenu = menu;
            menuLastUpdated = System.currentTimeMillis();

            // note that a WARNING_OR_ERROR menu can be a valid temporary state (cancelling TBR)
            // of a running command
            if (activeCmd == null && currentMenu.getType() == MenuType.WARNING_OR_ERROR) {
                log.warn("Warning/error menu encountered without a command running");
            }
        }

        @Override
        public void rtDisplayHandleNoMenu() throws RemoteException {
            log.debug("rtDisplayHandleNoMenu callback invoked");
        }

    };

    public boolean isPumpBusy() {
        return activeCmd != null; // || currentMenu == null || currentMenu.getType() != MenuType.MAIN_MENU;
    }

    private volatile Command activeCmd = null;

    // TODO take a callback to call when command finishes?
    // TODO sometimes hangs ... we should timeout and raise an alert to check the pump and ruffy
    // TODO not getting a menu update in a while is a good cue ...
    // fire up a monitoring thread for that?
    public CommandResult runCommand(final Command cmd) {
        try {
            if (isPumpBusy()) {
                return new CommandResult().message("Pump is busy");
            }
            ensureConnected();

            // TODO make this a safe lock
            synchronized (this) {
                cmdResult = null;
                activeCmd = cmd;
                final RuffyScripter scripter = this;
                // TODO hackish, to say the least ...
                // wait till pump is ready for input
                waitForMenuUpdate();
                log.debug("Cmd execution: connection ready, executing cmd " + cmd);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            cmdResult = cmd.execute(scripter);
                        } catch (Exception e) {
                            cmdResult = new CommandResult().exception(e).message("Unexpected exception running cmd");
                        } finally {
                            activeCmd = null;
                        }

                    }
                }).start();
            }

            // TODO really?
            while (activeCmd != null) {
                SystemClock.sleep(500);
                log.trace("Waiting for running command to complete");
            }
            log.debug("Command result: " + cmdResult);

            CommandResult r = cmdResult;
            cmdResult = null;
            return r;
        } catch (CommandException e) {
            return e.toCommandResult();
        } catch (Exception e) {
            return new CommandResult().exception(e).message("Unexpected exception communication with ruffy");
        }
    }

    public void ensureConnected() {
        // did we get a menu update from the pump in the last 5s? Then we're connected
        if (currentMenu != null && menuLastUpdated + 5000 > System.currentTimeMillis()) {
            log.debug("Pump is sending us menu updating, so we're connected");
           return;
        }

        try {
                boolean connectSuccesful = ruffyService.doRTConnect() == 0;
                log.debug("Connect init successful: " + connectSuccesful);
                while (currentMenu == null) {
                    log.debug("Waiting for first menu update to be sent");
                    waitForMenuUpdate();
                }
        } catch (RemoteException e) {
            throw new CommandException().exception(e).message("Unexpected exception while initiating/restoring pump connection");
        }
    }

    public CommandResult disconnect() {
        try {
            ruffyService.doRTDisconnect();
        } catch (RemoteException e) {
            return new CommandResult().exception(e).message("Unexpected exception trying to disconnect");
        }
        return new CommandResult().success(true);
    }

    // below: methods to be used by commands

    private static class Key {
        static byte NO_KEY = (byte) 0x00;
        static byte MENU = (byte) 0x03;
        static byte CHECK = (byte) 0x0C;
        static byte UP = (byte) 0x30;
        static byte DOWN = (byte) 0xC0;
    }

    public void pressUpKey() {
        pressKey(Key.UP);
    }

    public void pressDownKey() {
        pressKey(Key.DOWN);
    }

    public void pressCheckKey() {
        pressKey(Key.CHECK);
    }

    public void pressMenuKey() {
        // TODO build 'wait for menu update' into this method? get current menu, press key, wait for update?
        pressKey(Key.MENU);
    }

    /** Wait until the menu update is in */
    public void waitForMenuUpdate() {
        long timeoutExpired = System.currentTimeMillis() + 90 * 1000;
        long initialUpdateTime = menuLastUpdated;
        while (initialUpdateTime == menuLastUpdated) {
            if(System.currentTimeMillis() > timeoutExpired) {
                throw new CommandException().message("Timeout waiting for menu update");
            }
            SystemClock.sleep(50);
        }
    }

    /**
     * "Virtual" key, emulated by pressing menu and up simultaneously
     */
    // Doesn't work
/*    public void pressBackKey() throws RemoteException {
        ruffyService.rtSendKey(Key.MENU, true);
        SystemClock.sleep(50);
        ruffyService.rtSendKey(Key.UP, true);
        SystemClock.sleep(100);
        ruffyService.rtSendKey(Key.NO_KEY, true);
    }*/

    private void pressKey(final byte key) {
        try {
            ruffyService.rtSendKey(key, true);
            SystemClock.sleep(100);
            ruffyService.rtSendKey(Key.NO_KEY, true);
        } catch (RemoteException e) {
            throw new CommandException().exception(e).message("Error while pressing buttons");
        }
    }

    public void navigateToMenu(MenuType desiredMenu) {
        // TODO menu var might not have been initialized if this is called to early
        // though that's gonna be a problem for all code;
        // wait during init till this is set? create a getter for currentMenu to do this?
        MenuType startedFrom = currentMenu.getType();
        while (currentMenu.getType() != desiredMenu) {
            MenuType currentType = currentMenu.getType();
/*            if (currentType == startedFrom) {
                // TODO don't trigger right away, that's always a match ;-)
                // failed to find the menu, after going through all the menus, bail out
                throw new CommandException(false, null, "Menu not found searching for " + desiredMenu);
            }*/
            pressMenuKey();
            waitForMenuToBeLeft(currentType);
        }
    }

    /**
     * Wait till a menu changed has completed, "away" from the menu provided as argument.
     */
    public void waitForMenuToBeLeft(MenuType menuType) {
        while (currentMenu.getType() == menuType) {
            SystemClock.sleep(250);
        }
    }

    public void verifyMenuIsDisplayed(MenuType menu) {
        String message = "Invalid pump state, expected to be in menu "
                + menu + ", but current menu is " + currentMenu.getType();
        verifyMenuIsDisplayed(menu, message);
    }

    public void verifyMenuIsDisplayed(MenuType menu, String message) {
        waitForMenuUpdate();
        if (currentMenu.getType() != menu) {
            throw new CommandException().message(message);
        }
    }
}