package com.earth2me.essentials;

import com.earth2me.essentials.commands.IEssentialsCommand;
import com.earth2me.essentials.economy.EconomyLayer;
import com.earth2me.essentials.economy.EconomyLayers;
import com.earth2me.essentials.messaging.IMessageRecipient;
import com.earth2me.essentials.messaging.SimpleMessageRecipient;
import com.earth2me.essentials.utils.DateUtil;
import com.earth2me.essentials.utils.EnumUtil;
import com.earth2me.essentials.utils.FormatUtil;
import com.earth2me.essentials.utils.NumberUtil;
import com.earth2me.essentials.utils.TriState;
import com.earth2me.essentials.utils.VersionUtil;
import com.google.common.collect.Lists;
import net.ess3.api.IEssentials;
import net.ess3.api.MaxMoneyException;
import net.ess3.api.events.AfkStatusChangeEvent;
import net.ess3.api.events.JailStatusChangeEvent;
import net.ess3.api.events.MuteStatusChangeEvent;
import net.ess3.api.events.UserBalanceUpdateEvent;
import net.essentialsx.api.v2.events.TransactionEvent;
import net.essentialsx.api.v2.services.mail.MailSender;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.earth2me.essentials.I18n.tl;

public class User extends UserData implements Comparable<User>, IMessageRecipient, net.ess3.api.IUser {
    private static final Statistic PLAY_ONE_TICK = EnumUtil.getStatistic("PLAY_ONE_MINUTE", "PLAY_ONE_TICK");
    private static final Logger logger = Logger.getLogger("Essentials");

    // User modules
    private final IMessageRecipient messageRecipient;
    private transient final AsyncTeleport teleport;
    private transient final Teleport legacyTeleport;

    // User command confirmation strings
    private final Map<User, BigDecimal> confirmingPayments = new WeakHashMap<>();

    // User teleport variables
    private final transient LinkedHashMap<String, TpaRequest> teleportRequestQueue = new LinkedHashMap<>();

    // User properties
    private transient boolean vanished;
    private boolean hidden = false;
    private boolean rightClickJump = false;
    private boolean invSee = false;
    private boolean recipeSee = false;
    private boolean enderSee = false;
    private boolean ignoreMsg = false;

    // User afk variables
    private String afkMessage;
    private long afkSince;
    private transient Location afkPosition = null;

    // Misc
    private transient long lastOnlineActivity;
    private transient long lastThrottledAction;
    private transient long lastActivity = System.currentTimeMillis();
    private transient long teleportInvulnerabilityTimestamp = 0;
    private String confirmingClearCommand;
    private long lastNotifiedAboutMailsMs;
    private String lastHomeConfirmation;
    private long lastHomeConfirmationTimestamp;
    private Boolean toggleShout;
    private transient final List<String> signCopy = Lists.newArrayList("", "", "", "");

    public User(final Player base, final IEssentials ess) {
        super(base, ess);
        teleport = new AsyncTeleport(this, ess);
        legacyTeleport = new Teleport(this, ess);
        if (isAfk()) {
            afkPosition = this.getLocation();
        }
        if (this.getBase().isOnline()) {
            lastOnlineActivity = System.currentTimeMillis();
        }
        this.messageRecipient = new SimpleMessageRecipient(ess, this);
    }

    void update(final Player base) {
        setBase(base);
    }

    public IEssentials getEssentials() {
        return ess;
    }

    @Override
    public boolean isAuthorized(final IEssentialsCommand cmd) {
        return isAuthorized(cmd, "essentials.");
    }

    @Override
    public boolean isAuthorized(final IEssentialsCommand cmd, final String permissionPrefix) {
        return isAuthorized(permissionPrefix + (cmd.getName().equals("r") ? "msg" : cmd.getName()));
    }

    @Override
    public boolean isAuthorized(final String node) {
        final boolean result = isAuthorizedCheck(node);
        if (ess.getSettings().isDebug()) {
            ess.getLogger().log(Level.INFO, "checking if " + base.getName() + " has " + node + " - " + result);
        }
        return result;
    }

    @Override
    public boolean isPermissionSet(final String node) {
        final boolean result = isPermSetCheck(node);
        if (ess.getSettings().isDebug()) {
            ess.getLogger().log(Level.INFO, "checking if " + base.getName() + " has " + node + " (set-explicit) - " + result);
        }
        return result;
    }

    /**
     * Checks if the given permission is explicitly defined and returns its value, otherwise
     * {@link TriState#UNSET}.
     */
    public TriState isAuthorizedExact(final String node) {
        return isAuthorizedExactCheck(node);
    }

    private boolean isAuthorizedCheck(final String node) {
        if (base instanceof OfflinePlayer) {
            return false;
        }

        try {
            return ess.getPermissionsHandler().hasPermission(base, node);
        } catch (final Exception ex) {
            if (ess.getSettings().isDebug()) {
                ess.getLogger().log(Level.SEVERE, "Permission System Error: " + ess.getPermissionsHandler().getName() + " returned: " + ex.getMessage(), ex);
            } else {
                ess.getLogger().log(Level.SEVERE, "Permission System Error: " + ess.getPermissionsHandler().getName() + " returned: " + ex.getMessage());
            }

            return false;
        }
    }

    private boolean isPermSetCheck(final String node) {
        if (base instanceof OfflinePlayer) {
            return false;
        }

        try {
            return ess.getPermissionsHandler().isPermissionSet(base, node);
        } catch (final Exception ex) {
            if (ess.getSettings().isDebug()) {
                ess.getLogger().log(Level.SEVERE, "Permission System Error: " + ess.getPermissionsHandler().getName() + " returned: " + ex.getMessage(), ex);
            } else {
                ess.getLogger().log(Level.SEVERE, "Permission System Error: " + ess.getPermissionsHandler().getName() + " returned: " + ex.getMessage());
            }

            return false;
        }
    }

    private TriState isAuthorizedExactCheck(final String node) {
        if (base instanceof OfflinePlayer) {
            return TriState.UNSET;
        }

        try {
            return ess.getPermissionsHandler().isPermissionSetExact(base, node);
        } catch (final Exception ex) {
            if (ess.getSettings().isDebug()) {
                ess.getLogger().log(Level.SEVERE, "Permission System Error: " + ess.getPermissionsHandler().getName() + " returned: " + ex.getMessage(), ex);
            } else {
                ess.getLogger().log(Level.SEVERE, "Permission System Error: " + ess.getPermissionsHandler().getName() + " returned: " + ex.getMessage());
            }

            return TriState.UNSET;
        }
    }

    @Override
    public void healCooldown() throws Exception {
        final Calendar now = new GregorianCalendar();
        if (getLastHealTimestamp() > 0) {
            final double cooldown = ess.getSettings().getHealCooldown();
            final Calendar cooldownTime = new GregorianCalendar();
            cooldownTime.setTimeInMillis(getLastHealTimestamp());
            cooldownTime.add(Calendar.SECOND, (int) cooldown);
            cooldownTime.add(Calendar.MILLISECOND, (int) ((cooldown * 1000.0) % 1000.0));
            if (cooldownTime.after(now) && !isAuthorized("essentials.heal.cooldown.bypass")) {
                throw new Exception(tl("timeBeforeHeal", DateUtil.formatDateDiff(cooldownTime.getTimeInMillis())));
            }
        }
        setLastHealTimestamp(now.getTimeInMillis());
    }

    @Override
    public void giveMoney(final BigDecimal value) throws MaxMoneyException {
        giveMoney(value, null);
    }

    @Override
    public void giveMoney(final BigDecimal value, final CommandSource initiator) throws MaxMoneyException {
        giveMoney(value, initiator, UserBalanceUpdateEvent.Cause.UNKNOWN);
    }

    public void giveMoney(final BigDecimal value, final CommandSource initiator, final UserBalanceUpdateEvent.Cause cause) throws MaxMoneyException {
        if (value.signum() == 0) {
            return;
        }
        setMoney(getMoney().add(value), cause);
        sendMessage(tl("addedToAccount", NumberUtil.displayCurrency(value, ess)));
        if (initiator != null) {
            initiator.sendMessage(tl("addedToOthersAccount", NumberUtil.displayCurrency(value, ess), this.getDisplayName(), NumberUtil.displayCurrency(getMoney(), ess)));
        }
    }

    @Override
    public void payUser(final User reciever, final BigDecimal value) throws Exception {
        payUser(reciever, value, UserBalanceUpdateEvent.Cause.UNKNOWN);
    }

    public void payUser(final User reciever, final BigDecimal value, final UserBalanceUpdateEvent.Cause cause) throws Exception {
        if (value.compareTo(BigDecimal.ZERO) < 1) {
            throw new Exception(tl("payMustBePositive"));
        }

        if (canAfford(value)) {
            setMoney(getMoney().subtract(value), cause);
            reciever.setMoney(reciever.getMoney().add(value), cause);
            sendMessage(tl("moneySentTo", NumberUtil.displayCurrency(value, ess), reciever.getDisplayName()));
            reciever.sendMessage(tl("moneyRecievedFrom", NumberUtil.displayCurrency(value, ess), getDisplayName()));
            final TransactionEvent transactionEvent = new TransactionEvent(this.getSource(), reciever, value);
            ess.getServer().getPluginManager().callEvent(transactionEvent);
        } else {
            throw new ChargeException(tl("notEnoughMoney", NumberUtil.displayCurrency(value, ess)));
        }
    }

    @Override
    public void takeMoney(final BigDecimal value) {
        takeMoney(value, null);
    }

    @Override
    public void takeMoney(final BigDecimal value, final CommandSource initiator) {
        takeMoney(value, initiator, UserBalanceUpdateEvent.Cause.UNKNOWN);
    }

    public void takeMoney(final BigDecimal value, final CommandSource initiator, final UserBalanceUpdateEvent.Cause cause) {
        if (value.signum() == 0) {
            return;
        }
        try {
            setMoney(getMoney().subtract(value), cause);
        } catch (final MaxMoneyException ex) {
            ess.getLogger().log(Level.WARNING, "Invalid call to takeMoney, total balance can't be more than the max-money limit.", ex);
        }
        sendMessage(tl("takenFromAccount", NumberUtil.displayCurrency(value, ess)));
        if (initiator != null) {
            initiator.sendMessage(tl("takenFromOthersAccount", NumberUtil.displayCurrency(value, ess), this.getDisplayName(), NumberUtil.displayCurrency(getMoney(), ess)));
        }
    }

    @Override
    public boolean canAfford(final BigDecimal cost) {
        return canAfford(cost, true);
    }

    public boolean canAfford(final BigDecimal cost, final boolean permcheck) {
        if (cost.signum() <= 0) {
            return true;
        }
        final BigDecimal remainingBalance = getMoney().subtract(cost);
        if (!permcheck || isAuthorized("essentials.eco.loan")) {
            return remainingBalance.compareTo(ess.getSettings().getMinMoney()) >= 0;
        }
        return remainingBalance.signum() >= 0;
    }

    public void dispose() {
        ess.runTaskAsynchronously(this::_dispose);
    }

    private void _dispose() {
        if (!base.isOnline()) {
            this.base = new OfflinePlayer(getConfigUUID(), ess.getServer());
        }
        cleanup();
    }

    @Override
    public Boolean canSpawnItem(final Material material) {
        if (ess.getSettings().permissionBasedItemSpawn()) {
            final String name = material.toString().toLowerCase(Locale.ENGLISH).replace("_", "");

            if (isAuthorized("essentials.itemspawn.item-all") || isAuthorized("essentials.itemspawn.item-" + name))
                return true;

            if (VersionUtil.PRE_FLATTENING) {
                final int id = material.getId();
                if (isAuthorized("essentials.itemspawn.item-" + id)) return true;
            }

            return false;
        }

        return isAuthorized("essentials.itemspawn.exempt") || !ess.getSettings().itemSpawnBlacklist().contains(material);
    }

    @Override
    public void setLastLocation() {
        setLastLocation(this.getLocation());
    }

    @Override
    public void setLogoutLocation() {
        setLogoutLocation(this.getLocation());
    }

    @Override
    public void requestTeleport(final User player, final boolean here) {
        final TpaRequest request = teleportRequestQueue.getOrDefault(player.getName(), new TpaRequest(player.getName(), player.getUUID()));
        request.setTime(System.currentTimeMillis());
        request.setHere(here);
        request.setLocation(here ? player.getLocation() : this.getLocation());

        // Handle max queue size
        teleportRequestQueue.remove(request.getName());
        if (teleportRequestQueue.size() >= ess.getSettings().getTpaMaxRequests()) {
            final List<String> keys = new ArrayList<>(teleportRequestQueue.keySet());
            teleportRequestQueue.remove(keys.get(keys.size() - 1));
        }

        // Add request to queue
        teleportRequestQueue.put(request.getName(), request);
    }

    @Override
    @Deprecated
    public boolean hasOutstandingTeleportRequest() {
        return getNextTpaRequest(false, false, false) != null;
    }

    public Collection<String> getPendingTpaKeys() {
        return teleportRequestQueue.keySet();
    }

    @Override
    public boolean hasPendingTpaRequests(boolean inform, boolean excludeHere) {
        return getNextTpaRequest(inform, false, excludeHere) != null;
    }

    public boolean hasOutstandingTpaRequest(String playerUsername, boolean here) {
        final TpaRequest request = getOutstandingTpaRequest(playerUsername, false);
        return request != null && request.isHere() == here;
    }

    public @Nullable TpaRequest getOutstandingTpaRequest(String playerUsername, boolean inform) {
        if (!teleportRequestQueue.containsKey(playerUsername)) {
            return null;
        }

        final long timeout = ess.getSettings().getTpaAcceptCancellation();
        final TpaRequest request = teleportRequestQueue.get(playerUsername);
        if (timeout < 1 || System.currentTimeMillis() - request.getTime() <= timeout * 1000) {
            return request;
        }
        teleportRequestQueue.remove(playerUsername);
        if (inform) {
            sendMessage(tl("requestTimedOutFrom", ess.getUser(request.getRequesterUuid()).getDisplayName()));
        }
        return null;
    }

    public TpaRequest removeTpaRequest(String playerUsername) {
        return teleportRequestQueue.remove(playerUsername);
    }

    @Override
    public TpaRequest getNextTpaRequest(boolean inform, boolean ignoreExpirations, boolean excludeHere) {
        if (teleportRequestQueue.isEmpty()) {
            return null;
        }

        final long timeout = ess.getSettings().getTpaAcceptCancellation();
        final List<String> keys = new ArrayList<>(teleportRequestQueue.keySet());
        Collections.reverse(keys);

        TpaRequest nextRequest = null;
        for (final String key : keys) {
            final TpaRequest request = teleportRequestQueue.get(key);
            if (timeout < 1 || (System.currentTimeMillis() - request.getTime()) <= TimeUnit.SECONDS.toMillis(timeout)) {
                if (excludeHere && request.isHere()) {
                    continue;
                }

                if (ignoreExpirations) {
                    return request;
                } else if (nextRequest == null) {
                    nextRequest = request;
                }
            } else {
                if (inform) {
                    sendMessage(tl("requestTimedOutFrom", ess.getUser(request.getRequesterUuid()).getDisplayName()));
                }
                teleportRequestQueue.remove(key);
            }
        }
        return nextRequest;
    }

    public String getNick() {
        return getNick(true, true);
    }

    /**
     * Needed for backwards compatibility.
     */
    public String getNick(final boolean longnick) {
        return getNick(true, true);
    }

    /**
     * Needed for backwards compatibility.
     */
    public String getNick(final boolean longnick, final boolean withPrefix, final boolean withSuffix) {
        return getNick(withPrefix, withSuffix);
    }

    public String getNick(final boolean withPrefix, final boolean withSuffix) {
        final StringBuilder prefix = new StringBuilder();
        final String nickname;
        String suffix = "";
        final String nick = getNickname();
        if (ess.getSettings().isCommandDisabled("nick") || nick == null || nick.isEmpty() || nick.equals(getName())) {
            nickname = getName();
        } else if (nick.equalsIgnoreCase(getName())) {
            nickname = nick;
        } else {
            nickname = FormatUtil.replaceFormat(ess.getSettings().getNicknamePrefix()) + nick;
            suffix = "§r";
        }

        if (this.getBase().isOp()) {
            try {
                final String opPrefix = ess.getSettings().getOperatorColor();
                if (opPrefix != null && !opPrefix.isEmpty()) {
                    prefix.insert(0, opPrefix);
                    suffix = "§r";
                }
            } catch (final Exception e) {
                if (ess.getSettings().isDebug()) {
                    e.printStackTrace();
                }
            }
        }

        if (ess.getSettings().addPrefixSuffix()) {
            //These two extra toggles are not documented, because they are mostly redundant #EasterEgg
            if (withPrefix || !ess.getSettings().disablePrefix()) {
                final String ptext = FormatUtil.replaceFormat(ess.getPermissionsHandler().getPrefix(base));
                prefix.insert(0, ptext);
                suffix = "§r";
            }
            if (withSuffix || !ess.getSettings().disableSuffix()) {
                final String stext = FormatUtil.replaceFormat(ess.getPermissionsHandler().getSuffix(base));
                suffix = stext + "§r";
                // :YEP: WHAT ARE THEY DOING?
                // :YEP: STILL. LEGACY CODE.
                // :YEP: BUT WHY?
                // :YEP: I CAN'T BELIEVE THIS!
                // Code from 1542 BC #EasterEgg
                suffix = suffix.replace("§f§r", "§r").replace("§r§r", "§r");
            }
        }
        final String strPrefix = prefix.toString();
        String output = strPrefix + nickname + suffix;
        if (output.charAt(output.length() - 1) == '§') {
            output = output.substring(0, output.length() - 1);
        }
        return output;
    }

    public void setDisplayNick() {
        if (base.isOnline() && ess.getSettings().changeDisplayName()) {
            this.getBase().setDisplayName(getNick(true));
            if (isAfk()) {
                updateAfkListName();
            } else if (ess.getSettings().changePlayerListName()) {
                final String name = getNick(ess.getSettings().isAddingPrefixInPlayerlist(), ess.getSettings().isAddingSuffixInPlayerlist());
                try {
                    this.getBase().setPlayerListName(name);
                } catch (final IllegalArgumentException e) {
                    if (ess.getSettings().isDebug()) {
                        logger.log(Level.INFO, "Playerlist for " + name + " was not updated. Name clashed with another online player.");
                    }
                }
            }
        }
    }

    @Override
    public String getDisplayName() {
        return super.getBase().getDisplayName() == null || (ess.getSettings().hideDisplayNameInVanish() && isHidden()) ? super.getBase().getName() : super.getBase().getDisplayName();
    }

    @Override
    public String getFormattedNickname() {
        final String rawNickname = getNickname();
        if (rawNickname == null) {
            return null;
        }
        return FormatUtil.replaceFormat(ess.getSettings().getNicknamePrefix() + rawNickname);
    }

    @Override
    public AsyncTeleport getAsyncTeleport() {
        return teleport;
    }

    /**
     * @deprecated This API is not asynchronous. Use {@link User#getAsyncTeleport()}
     */
    @Override
    @Deprecated
    public Teleport getTeleport() {
        return legacyTeleport;
    }

    public long getLastOnlineActivity() {
        return lastOnlineActivity;
    }

    public void setLastOnlineActivity(final long timestamp) {
        lastOnlineActivity = timestamp;
    }

    @Override
    public BigDecimal getMoney() {
        final long start = System.nanoTime();
        final BigDecimal value = _getMoney();
        final long elapsed = System.nanoTime() - start;
        if (elapsed > ess.getSettings().getEconomyLagWarning()) {
            ess.getLogger().log(Level.INFO, "Lag Notice - Slow Economy Response - Request took over {0}ms!", elapsed / 1000000.0);
        }
        return value;
    }

    @Override
    public void setMoney(final BigDecimal value) throws MaxMoneyException {
        setMoney(value, UserBalanceUpdateEvent.Cause.UNKNOWN);
    }

    private BigDecimal _getMoney() {
        if (ess.getSettings().isEcoDisabled()) {
            if (ess.getSettings().isDebug()) {
                ess.getLogger().info("Internal economy functions disabled, aborting balance check.");
            }
            return BigDecimal.ZERO;
        }
        final EconomyLayer layer = EconomyLayers.getSelectedLayer();
        if (layer != null && (layer.hasAccount(getBase()) || layer.createPlayerAccount(getBase()))) {
            return layer.getBalance(getBase());
        }
        return super.getMoney();
    }

    public void setMoney(final BigDecimal value, final UserBalanceUpdateEvent.Cause cause) throws MaxMoneyException {
        if (ess.getSettings().isEcoDisabled()) {
            if (ess.getSettings().isDebug()) {
                ess.getLogger().info("Internal economy functions disabled, aborting balance change.");
            }
            return;
        }
        final BigDecimal oldBalance = _getMoney();

        final UserBalanceUpdateEvent updateEvent = new UserBalanceUpdateEvent(this.getBase(), oldBalance, value, cause);
        ess.getServer().getPluginManager().callEvent(updateEvent);
        final BigDecimal newBalance = updateEvent.getNewBalance();

        final EconomyLayer layer = EconomyLayers.getSelectedLayer();
        if (layer != null && (layer.hasAccount(getBase()) || layer.createPlayerAccount(getBase()))) {
            layer.set(getBase(), newBalance);
        }
        super.setMoney(newBalance, true);
        Trade.log("Update", "Set", "API", getName(), new Trade(newBalance, ess), null, null, null, newBalance, ess);
    }

    public void updateMoneyCache(final BigDecimal value) {
        if (ess.getSettings().isEcoDisabled() || !EconomyLayers.isLayerSelected() || super.getMoney().equals(value)) {
            return;
        }
        try {
            super.setMoney(value, false);
        } catch (final MaxMoneyException ex) {
            // We don't want to throw any errors here, just updating a cache
        }
    }

    @Override
    public void setAfk(final boolean set) {
        setAfk(set, AfkStatusChangeEvent.Cause.UNKNOWN);
    }

    @Override
    public void setAfk(final boolean set, final AfkStatusChangeEvent.Cause cause) {
        final AfkStatusChangeEvent afkEvent = new AfkStatusChangeEvent(this, set, cause);
        ess.getServer().getPluginManager().callEvent(afkEvent);
        if (afkEvent.isCancelled()) {
            return;
        }

        this.getBase().setSleepingIgnored(this.isAuthorized("essentials.sleepingignored") || set && ess.getSettings().sleepIgnoresAfkPlayers());
        if (set && !isAfk()) {
            afkPosition = this.getLocation();
            this.afkSince = System.currentTimeMillis();
        } else if (!set && isAfk()) {
            afkPosition = null;
            this.afkMessage = null;
            this.afkSince = 0;
        }
        _setAfk(set);
        updateAfkListName();
    }

    private void updateAfkListName() {
        if (ess.getSettings().isAfkListName()) {
            if (isAfk()) {
                final String afkName = ess.getSettings().getAfkListName().replace("{PLAYER}", getDisplayName()).replace("{USERNAME}", getName());
                getBase().setPlayerListName(afkName);
            } else {
                getBase().setPlayerListName(null);
                setDisplayNick();
            }
        }
    }

    @Deprecated
    public boolean toggleAfk() {
        return toggleAfk(AfkStatusChangeEvent.Cause.UNKNOWN);
    }

    public boolean toggleAfk(final AfkStatusChangeEvent.Cause cause) {
        setAfk(!isAfk(), cause);
        return isAfk();
    }

    @Override
    public boolean isHiddenFrom(Player player) {
        return !player.canSee(getBase());
    }

    @Override
    public boolean isHidden() {
        return hidden;
    }

    @Override
    public void setHidden(final boolean hidden) {
        this.hidden = hidden;
        if (hidden) {
            setLastLogout(getLastOnlineActivity());
        }
    }

    public boolean isHidden(final Player player) {
        return hidden || !player.canSee(getBase());
    }

    @Override
    public String getFormattedJailTime() {
        return DateUtil.formatDateDiff(getOnlineJailedTime() > 0 ? getOnlineJailExpireTime() : getJailTimeout());
    }

    private long getOnlineJailExpireTime() {
        return ((getOnlineJailedTime() - getBase().getStatistic(PLAY_ONE_TICK)) * 50) + System.currentTimeMillis();
    }

    //Returns true if status expired during this check
    public boolean checkJailTimeout(final long currentTime) {
        if (getJailTimeout() > 0) {

            if (getOnlineJailedTime() > 0) {
                if (getOnlineJailedTime() > getBase().getStatistic(PLAY_ONE_TICK)) {
                    return false;
                }
            }

            if (getJailTimeout() < currentTime && isJailed()) {
                final JailStatusChangeEvent event = new JailStatusChangeEvent(this, null, false);
                ess.getServer().getPluginManager().callEvent(event);

                if (!event.isCancelled()) {
                    setJailTimeout(0);
                    setOnlineJailedTime(0);
                    setJailed(false);
                    sendMessage(tl("haveBeenReleased"));
                    setJail(null);
                    if (ess.getSettings().getTeleportWhenFreePolicy() == ISettings.TeleportWhenFreePolicy.BACK) {
                        final CompletableFuture<Boolean> future = new CompletableFuture<>();
                        getAsyncTeleport().back(future);
                        future.exceptionally(e -> {
                            getAsyncTeleport().respawn(null, TeleportCause.PLUGIN, new CompletableFuture<>());
                            return false;
                        });
                    } else if (ess.getSettings().getTeleportWhenFreePolicy() == ISettings.TeleportWhenFreePolicy.SPAWN) {
                        getAsyncTeleport().respawn(null, TeleportCause.PLUGIN, new CompletableFuture<>());
                    }
                    return true;
                }
            }
        }
        return false;
    }

    //Returns true if status expired during this check
    public boolean checkMuteTimeout(final long currentTime) {
        if (getMuteTimeout() > 0 && getMuteTimeout() < currentTime && isMuted()) {
            final MuteStatusChangeEvent event = new MuteStatusChangeEvent(this, null, false, getMuteTimeout(), getMuteReason());
            ess.getServer().getPluginManager().callEvent(event);

            if (!event.isCancelled()) {
                setMuteTimeout(0);
                sendMessage(tl("canTalkAgain"));
                setMuted(false);
                setMuteReason(null);
                return true;
            }
        }
        return false;
    }

    @Deprecated
    public void updateActivity(final boolean broadcast) {
        updateActivity(broadcast, AfkStatusChangeEvent.Cause.UNKNOWN);
    }

    public void updateActivity(final boolean broadcast, final AfkStatusChangeEvent.Cause cause) {
        if (isAfk()) {
            setAfk(false, cause);
            if (broadcast && !isHidden() && !isAfk()) {
                setDisplayNick();
                final String msg = tl("userIsNotAway", getDisplayName());
                final String selfmsg = tl("userIsNotAwaySelf", getDisplayName());
                if (!msg.isEmpty() && ess.getSettings().broadcastAfkMessage()) {
                    // exclude user from receiving general AFK announcement in favor of personal message
                    ess.broadcastMessage(this, msg, u -> u == this);
                }
                if (!selfmsg.isEmpty()) {
                    this.sendMessage(selfmsg);
                }
            }
        }
        lastActivity = System.currentTimeMillis();
    }

    public void updateActivityOnMove(final boolean broadcast) {
        if (ess.getSettings().cancelAfkOnMove()) {
            updateActivity(broadcast, AfkStatusChangeEvent.Cause.MOVE);
        }
    }

    public void updateActivityOnInteract(final boolean broadcast) {
        if (ess.getSettings().cancelAfkOnInteract()) {
            updateActivity(broadcast, AfkStatusChangeEvent.Cause.INTERACT);
        }
    }

    public void updateActivityOnChat(final boolean broadcast) {
        if (ess.getSettings().cancelAfkOnChat()) {
            //Chat happens async, make sure we have a sync context
            ess.scheduleSyncDelayedTask(() -> {
                updateActivity(broadcast, AfkStatusChangeEvent.Cause.CHAT);
            });
        }
    }

    public void checkActivity() {
        // Graceful time before the first afk check call. 
        if (System.currentTimeMillis() - lastActivity <= 10000) {
            return;
        }

        final long autoafkkick = ess.getSettings().getAutoAfkKick();
        if (autoafkkick > 0
                && lastActivity > 0 && (lastActivity + (autoafkkick * 1000)) < System.currentTimeMillis()
                && !isAuthorized("essentials.kick.exempt")
                && !isAuthorized("essentials.afk.kickexempt")) {
            final String kickReason = tl("autoAfkKickReason", autoafkkick / 60.0);
            lastActivity = 0;
            this.getBase().kickPlayer(kickReason);

            for (final User user : ess.getOnlineUsers()) {
                if (user.isAuthorized("essentials.kick.notify")) {
                    user.sendMessage(tl("playerKicked", Console.DISPLAY_NAME, getName(), kickReason));
                }
            }
        }
        final long autoafk = ess.getSettings().getAutoAfk();
        if (!isAfk() && autoafk > 0 && lastActivity + autoafk * 1000 < System.currentTimeMillis() && isAuthorized("essentials.afk.auto")) {
            setAfk(true, AfkStatusChangeEvent.Cause.ACTIVITY);
            if (isAfk() && !isHidden()) {
                setDisplayNick();
                final String msg = tl("userIsAway", getDisplayName());
                final String selfmsg = tl("userIsAwaySelf", getDisplayName());
                if (!msg.isEmpty() && ess.getSettings().broadcastAfkMessage()) {
                    // exclude user from receiving general AFK announcement in favor of personal message
                    ess.broadcastMessage(this, msg, u -> u == this);
                }
                if (!selfmsg.isEmpty()) {
                    this.sendMessage(selfmsg);
                }
            }
        }
    }

    public Location getAfkPosition() {
        return afkPosition;
    }

    @Override
    public boolean isGodModeEnabled() {
        if (super.isGodModeEnabled()) {
            // This enables the no-god-in-worlds functionality where the actual player god mode state is never modified in disabled worlds,
            // but this method gets called every time the player takes damage. In the case that the world has god-mode disabled then this method
            // will return false and the player will take damage, even though they are in god mode (isGodModeEnabledRaw()).
            if (!ess.getSettings().getNoGodWorlds().contains(this.getLocation().getWorld().getName())) {
                return true;
            }
        }
        if (isAfk()) {
            // Protect AFK players by representing them in a god mode state to render them invulnerable to damage.
            return ess.getSettings().getFreezeAfkPlayers();
        }
        return false;
    }

    public boolean isGodModeEnabledRaw() {
        return super.isGodModeEnabled();
    }

    @Override
    public String getGroup() {
        final String result = ess.getPermissionsHandler().getGroup(base);
        if (ess.getSettings().isDebug()) {
            ess.getLogger().log(Level.INFO, "looking up groupname of " + base.getName() + " - " + result);
        }
        return result;
    }

    @Override
    public boolean inGroup(final String group) {
        final boolean result = ess.getPermissionsHandler().inGroup(base, group);
        if (ess.getSettings().isDebug()) {
            ess.getLogger().log(Level.INFO, "checking if " + base.getName() + " is in group " + group + " - " + result);
        }
        return result;
    }

    @Override
    public boolean canBuild() {
        if (this.getBase().isOp()) {
            return true;
        }
        return ess.getPermissionsHandler().canBuild(base, getGroup());
    }

    @Override
    @Deprecated
    public long getTeleportRequestTime() {
        final TpaRequest request = getNextTpaRequest(false, false, false);
        return request == null ? 0L : request.getTime();
    }

    public boolean isInvSee() {
        return invSee;
    }

    public void setInvSee(final boolean set) {
        invSee = set;
    }

    public boolean isEnderSee() {
        return enderSee;
    }

    public void setEnderSee(final boolean set) {
        enderSee = set;
    }

    @Override
    public void enableInvulnerabilityAfterTeleport() {
        final long time = ess.getSettings().getTeleportInvulnerability();
        if (time > 0) {
            teleportInvulnerabilityTimestamp = System.currentTimeMillis() + time;
        }
    }

    @Override
    public void resetInvulnerabilityAfterTeleport() {
        if (teleportInvulnerabilityTimestamp != 0 && teleportInvulnerabilityTimestamp < System.currentTimeMillis()) {
            teleportInvulnerabilityTimestamp = 0;
        }
    }

    @Override
    public boolean hasInvulnerabilityAfterTeleport() {
        return teleportInvulnerabilityTimestamp != 0 && teleportInvulnerabilityTimestamp >= System.currentTimeMillis();
    }

    public boolean canInteractVanished() {
        return isAuthorized("essentials.vanish.interact");
    }

    @Override
    public boolean isIgnoreMsg() {
        return ignoreMsg;
    }

    @Override
    public void setIgnoreMsg(final boolean ignoreMsg) {
        this.ignoreMsg = ignoreMsg;
    }

    @Override
    public boolean isVanished() {
        return vanished;
    }

    @Override
    public void setVanished(final boolean set) {
        vanished = set;
        if (set) {
            for (final User user : ess.getOnlineUsers()) {
                if (!user.isAuthorized("essentials.vanish.see")) {
                    user.getBase().hidePlayer(getBase());
                }
            }
            setHidden(true);
            ess.getVanishedPlayersNew().add(getName());
            this.getBase().setMetadata("vanished", new FixedMetadataValue(ess, true));
            if (isAuthorized("essentials.vanish.effect")) {
                this.getBase().addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false));
            }
            if (ess.getSettings().sleepIgnoresVanishedPlayers()) {
                getBase().setSleepingIgnored(true);
            }
        } else {
            for (final Player p : ess.getOnlinePlayers()) {
                p.showPlayer(getBase());
            }
            setHidden(false);
            ess.getVanishedPlayersNew().remove(getName());
            this.getBase().setMetadata("vanished", new FixedMetadataValue(ess, false));
            if (isAuthorized("essentials.vanish.effect")) {
                this.getBase().removePotionEffect(PotionEffectType.INVISIBILITY);
            }
            if (ess.getSettings().sleepIgnoresVanishedPlayers() && !isAuthorized("essentials.sleepingignored")) {
                getBase().setSleepingIgnored(false);
            }
        }
    }

    public boolean checkSignThrottle() {
        if (isSignThrottled()) {
            return true;
        }
        updateThrottle();
        return false;
    }

    public boolean isSignThrottled() {
        final long minTime = lastThrottledAction + (1000 / ess.getSettings().getSignUsePerSecond());
        return System.currentTimeMillis() < minTime;
    }

    public void updateThrottle() {
        lastThrottledAction = System.currentTimeMillis();
    }

    public boolean isFlyClickJump() {
        return rightClickJump;
    }

    public void setRightClickJump(final boolean rightClickJump) {
        this.rightClickJump = rightClickJump;
    }

    @Override
    public boolean isIgnoreExempt() {
        return this.isAuthorized("essentials.chat.ignoreexempt");
    }

    public boolean isRecipeSee() {
        return recipeSee;
    }

    public void setRecipeSee(final boolean recipeSee) {
        this.recipeSee = recipeSee;
    }

    @Override
    public void sendMessage(final String message) {
        if (!message.isEmpty()) {
            base.sendMessage(message);
        }
    }

    @Override
    public int compareTo(final User other) {
        return FormatUtil.stripFormat(getDisplayName()).compareToIgnoreCase(FormatUtil.stripFormat(other.getDisplayName()));
    }

    @Override
    public boolean equals(final Object object) {
        if (!(object instanceof User)) {
            return false;
        }
        return this.getName().equalsIgnoreCase(((User) object).getName());

    }

    @Override
    public int hashCode() {
        return this.getName().hashCode();
    }

    @Override
    public CommandSource getSource() {
        return new CommandSource(getBase());
    }

    @Override
    public String getName() {
        return this.getBase().getName();
    }

    @Override
    public UUID getUUID() {
        return this.getBase().getUniqueId();
    }

    @Override
    public boolean isReachable() {
        return getBase().isOnline();
    }

    @Override
    public MessageResponse sendMessage(final IMessageRecipient recipient, final String message) {
        return this.messageRecipient.sendMessage(recipient, message);
    }

    @Override
    public MessageResponse onReceiveMessage(final IMessageRecipient sender, final String message) {
        return this.messageRecipient.onReceiveMessage(sender, message);
    }

    @Override
    public IMessageRecipient getReplyRecipient() {
        return this.messageRecipient.getReplyRecipient();
    }

    @Override
    public void setReplyRecipient(final IMessageRecipient recipient) {
        this.messageRecipient.setReplyRecipient(recipient);
    }

    @Override
    public String getAfkMessage() {
        return this.afkMessage;
    }

    @Override
    public void setAfkMessage(final String message) {
        if (isAfk()) {
            this.afkMessage = message;
        }
    }

    @Override
    public long getAfkSince() {
        return afkSince;
    }

    @Override
    public Map<User, BigDecimal> getConfirmingPayments() {
        return confirmingPayments;
    }

    public String getConfirmingClearCommand() {
        return confirmingClearCommand;
    }

    public void setConfirmingClearCommand(final String command) {
        this.confirmingClearCommand = command;
    }

    /**
     * Returns the {@link ItemStack} in the main hand or off-hand. If the main hand is empty then the offhand item is returned - also nullable.
     */
    public ItemStack getItemInHand() {
        if (VersionUtil.getServerBukkitVersion().isLowerThan(VersionUtil.v1_9_R01)) {
            return getBase().getInventory().getItemInHand();
        } else {
            final PlayerInventory inventory = getBase().getInventory();
            return inventory.getItemInMainHand() != null ? inventory.getItemInMainHand() : inventory.getItemInOffHand();
        }
    }

    @Override
    public void sendMail(MailSender sender, String message) {
        sendMail(sender, message, 0);
    }

    @Override
    public void sendMail(MailSender sender, String message, long expireAt) {
        ess.getMail().sendMail(this, sender, message, expireAt);
    }

    @Override
    @Deprecated
    public void addMail(String mail) {
        ess.getMail().sendLegacyMail(this, mail);
    }

    public void notifyOfMail() {
        final int unread = getUnreadMailAmount();
        if (unread != 0) {
            final int notifyPlayerOfMailCooldown = ess.getSettings().getNotifyPlayerOfMailCooldown() * 1000;
            if (System.currentTimeMillis() - lastNotifiedAboutMailsMs >= notifyPlayerOfMailCooldown) {
                sendMessage(tl("youHaveNewMail", unread));
                lastNotifiedAboutMailsMs = System.currentTimeMillis();
            }
        }
    }

    public String getLastHomeConfirmation() {
        return lastHomeConfirmation;
    }

    public void setLastHomeConfirmation(final String lastHomeConfirmation) {
        this.lastHomeConfirmation = lastHomeConfirmation;
    }

    public long getLastHomeConfirmationTimestamp() {
        return lastHomeConfirmationTimestamp;
    }

    public void setLastHomeConfirmationTimestamp() {
        this.lastHomeConfirmationTimestamp = System.currentTimeMillis();
    }

    public List<String> getSignCopy() {
        return signCopy;
    }

    public boolean isBaltopExempt() {
        if (getBase().isOnline()) {
            final boolean exempt = isAuthorized("essentials.balancetop.exclude");
            setBaltopExemptCache(exempt);
            return exempt;
        }
        return isBaltopExcludeCache();
    }

    @Override
    public Block getTargetBlock(int maxDistance) {
        final Block block;
        if (VersionUtil.getServerBukkitVersion().isLowerThan(VersionUtil.v1_13_2_R01) || (block = base.getTargetBlockExact(maxDistance)) == null) {
            return base.getTargetBlock(null, maxDistance);
        }
        return block;
    }

    @Override
    public void setToggleShout(boolean toggleShout) {
        this.toggleShout = toggleShout;
        if (ess.getSettings().isPersistShout()) {
            setShouting(toggleShout);
        }
    }

    @Override
    public boolean isToggleShout() {
        if (ess.getSettings().isPersistShout()) {
            return toggleShout = isShouting();
        }
        return toggleShout == null ? toggleShout = ess.getSettings().isShoutDefault() : toggleShout;
    }
}
