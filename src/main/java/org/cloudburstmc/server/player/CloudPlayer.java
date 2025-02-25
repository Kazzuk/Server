package org.cloudburstmc.server.player;

import co.aikar.timings.Timing;
import co.aikar.timings.Timings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Strings;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.nukkitx.math.vector.Vector2f;
import com.nukkitx.math.vector.Vector3f;
import com.nukkitx.math.vector.Vector3i;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtMapBuilder;
import com.nukkitx.nbt.NbtType;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.BedrockServerSession;
import com.nukkitx.protocol.bedrock.BedrockSession;
import com.nukkitx.protocol.bedrock.data.*;
import com.nukkitx.protocol.bedrock.data.command.CommandPermission;
import com.nukkitx.protocol.bedrock.data.entity.EntityEventType;
import com.nukkitx.protocol.bedrock.data.inventory.ContainerId;
import com.nukkitx.protocol.bedrock.data.skin.SerializedSkin;
import com.nukkitx.protocol.bedrock.handler.BatchHandler;
import com.nukkitx.protocol.bedrock.packet.*;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.bytes.ByteOpenHashSet;
import it.unimi.dsi.fastutil.bytes.ByteSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import lombok.extern.log4j.Log4j2;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.api.block.Block;
import org.cloudburstmc.api.block.BlockState;
import org.cloudburstmc.api.block.BlockTypes;
import org.cloudburstmc.api.blockentity.BlockEntity;
import org.cloudburstmc.api.blockentity.EnderChest;
import org.cloudburstmc.api.blockentity.Sign;
import org.cloudburstmc.api.command.CommandSender;
import org.cloudburstmc.api.crafting.CraftingGrid;
import org.cloudburstmc.api.enchantment.EnchantmentTypes;
import org.cloudburstmc.api.entity.Attribute;
import org.cloudburstmc.api.entity.Entity;
import org.cloudburstmc.api.entity.EntityTypes;
import org.cloudburstmc.api.entity.Interactable;
import org.cloudburstmc.api.entity.misc.DroppedItem;
import org.cloudburstmc.api.entity.misc.ExperienceOrb;
import org.cloudburstmc.api.entity.projectile.Arrow;
import org.cloudburstmc.api.entity.projectile.FishingHook;
import org.cloudburstmc.api.entity.projectile.ThrownTrident;
import org.cloudburstmc.api.event.entity.EntityDamageByBlockEvent;
import org.cloudburstmc.api.event.entity.EntityDamageByEntityEvent;
import org.cloudburstmc.api.event.entity.EntityDamageEvent;
import org.cloudburstmc.api.event.entity.ProjectileLaunchEvent;
import org.cloudburstmc.api.event.inventory.InventoryPickupArrowEvent;
import org.cloudburstmc.api.event.inventory.InventoryPickupItemEvent;
import org.cloudburstmc.api.event.player.*;
import org.cloudburstmc.api.inventory.Inventory;
import org.cloudburstmc.api.inventory.InventoryHolder;
import org.cloudburstmc.api.item.ItemStack;
import org.cloudburstmc.api.item.ItemTypes;
import org.cloudburstmc.api.item.data.Damageable;
import org.cloudburstmc.api.level.ChunkLoader;
import org.cloudburstmc.api.level.Difficulty;
import org.cloudburstmc.api.level.Location;
import org.cloudburstmc.api.level.chunk.Chunk;
import org.cloudburstmc.api.level.gamerule.GameRules;
import org.cloudburstmc.api.locale.TextContainer;
import org.cloudburstmc.api.permission.Permission;
import org.cloudburstmc.api.permission.PermissionAttachment;
import org.cloudburstmc.api.permission.PermissionAttachmentInfo;
import org.cloudburstmc.api.player.AdventureSetting;
import org.cloudburstmc.api.player.GameMode;
import org.cloudburstmc.api.player.Player;
import org.cloudburstmc.api.player.skin.Skin;
import org.cloudburstmc.api.plugin.PluginContainer;
import org.cloudburstmc.api.util.AxisAlignedBB;
import org.cloudburstmc.api.util.LoginChainData;
import org.cloudburstmc.api.util.SimpleAxisAlignedBB;
import org.cloudburstmc.server.Achievement;
import org.cloudburstmc.server.CloudAdventureSettings;
import org.cloudburstmc.server.CloudServer;
import org.cloudburstmc.server.blockentity.EnderChestBlockEntity;
import org.cloudburstmc.server.blockentity.SignBlockEntity;
import org.cloudburstmc.server.entity.BaseEntity;
import org.cloudburstmc.server.entity.EntityHuman;
import org.cloudburstmc.server.entity.EntityLiving;
import org.cloudburstmc.server.entity.projectile.EntityArrow;
import org.cloudburstmc.server.event.server.PlayerPacketSendEvent;
import org.cloudburstmc.server.form.CustomForm;
import org.cloudburstmc.server.form.Form;
import org.cloudburstmc.server.inventory.CloudCraftingGrid;
import org.cloudburstmc.server.inventory.CloudEnderChestInventory;
import org.cloudburstmc.server.inventory.CloudPlayerInventory;
import org.cloudburstmc.server.inventory.PlayerCursorInventory;
import org.cloudburstmc.server.inventory.transaction.CraftItemStackTransaction;
import org.cloudburstmc.server.item.ItemUtils;
import org.cloudburstmc.server.level.CloudLevel;
import org.cloudburstmc.server.level.Sound;
import org.cloudburstmc.server.level.biome.Biome;
import org.cloudburstmc.server.level.chunk.CloudChunk;
import org.cloudburstmc.server.locale.TranslationContainer;
import org.cloudburstmc.server.math.BlockRayTrace;
import org.cloudburstmc.server.math.NukkitMath;
import org.cloudburstmc.server.network.NetworkUtils;
import org.cloudburstmc.server.permission.PermissibleBase;
import org.cloudburstmc.server.player.handler.PlayerPacketHandler;
import org.cloudburstmc.server.player.manager.PlayerChunkManager;
import org.cloudburstmc.server.player.manager.PlayerInventoryManager;
import org.cloudburstmc.server.registry.CloudItemRegistry;
import org.cloudburstmc.server.registry.CommandRegistry;
import org.cloudburstmc.server.registry.EntityRegistry;
import org.cloudburstmc.server.utils.ClientChainData;
import org.cloudburstmc.server.utils.DummyBossBar;
import org.cloudburstmc.server.utils.TextFormat;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.nukkitx.protocol.bedrock.data.entity.EntityData.BED_POSITION;
import static com.nukkitx.protocol.bedrock.data.entity.EntityData.INTERACTIVE_TAG;
import static com.nukkitx.protocol.bedrock.data.entity.EntityFlag.USING_ITEM;

/**
 * @author MagicDroidX &amp; Box
 * Nukkit Project
 */
@Log4j2
public class CloudPlayer extends EntityHuman implements CommandSender, InventoryHolder, ChunkLoader, Player {

    public static final float DEFAULT_SPEED = 0.1f;
    public static final float MAXIMUM_SPEED = 0.5f;

    protected final BedrockServerSession session;

    private final PlayerPacketHandler packetHandler;

    private boolean initialized;
    private boolean inventoryOpen;
    public boolean spawned = false;
    public boolean loggedIn = false;
    public long lastBreak;
    public Vector3f speed = null;

    protected final BiMap<Inventory, Byte> windows = HashBiMap.create();
    protected final BiMap<Byte, Inventory> windowIndex = windows.inverse();
    protected final ByteSet permanentWindows = new ByteOpenHashSet();
    protected byte windowCnt = 4;

    protected final PlayerData playerData = new PlayerData();

    protected int messageCounter = 2;

    private String clientSecret;
    protected Vector3f forceMovement = null;

    private final PlayerInventoryManager invManager = new PlayerInventoryManager(this);

    public long creationTime = 0;

    protected long randomClientId;
    protected Vector3f teleportPosition = null;
    protected final PlayerFood foodData = new PlayerFood(this, 20, 20);

    protected boolean connected = true;
    protected boolean removeFormat = true;

    protected String username;
    protected String iusername;
    protected String displayName;

    protected int startAction = -1;
    protected Vector3f newPosition = null;

    private int loaderId;

    protected float stepHeight = 0.6f;

    protected final Map<UUID, CloudPlayer> hiddenPlayers = new HashMap<>();

    protected int viewDistance;
    protected final int chunksPerTick;
    protected final int spawnThreshold;
    private final Queue<BedrockPacket> inboundQueue = new ConcurrentLinkedQueue<>();

    protected int inAirTicks = 0;
    protected int startAirTicks = 5;

    protected CloudAdventureSettings adventureSettings;

    protected Vector3i sleeping = null;

    private PermissibleBase perm = null;

    private int exp = 0;
    private int expLevel = 0;
    protected Location spawnLocation = null;

    private Entity killer = null;

    private final AtomicReference<Locale> locale = new AtomicReference<>(null);

    private int hash;

    private String buttonText = "Button";

    protected boolean enableClientCommand = true;

    private EnderChest viewingEnderChest = null;

    protected int lastEnderPearl = 20;
    protected int lastChorusFruitTeleport = 20;

    private LoginChainData loginChainData;

    public Block breakingBlock = null;

    public int pickedXPOrb = 0;

    protected AtomicInteger formWindowCount = new AtomicInteger(0);
    protected Map<Integer, Form<?>> formWindows = new Int2ObjectOpenHashMap<>();
    //TODO: better handling server settings?
    protected CustomForm serverSettings = null;
    protected int serverSettingsId = -1;

    protected Map<Long, DummyBossBar> dummyBossBars = new Long2ObjectLinkedOpenHashMap<>();

    public FishingHook fishing = null;

    private final PlayerChunkManager chunkManager = new PlayerChunkManager(this);

    public int packetsRecieved;

    public long lastSkinChange;

    public CloudPlayer(BedrockServerSession session, ClientChainData chainData) {
        super(EntityTypes.PLAYER, Location.from(CloudServer.getInstance().getDefaultLevel()));
        this.session = session;
        this.packetHandler = new PlayerPacketHandler(this);
        session.setBatchHandler(new Handler());
        this.perm = new PermissibleBase(this);
        this.server = CloudServer.getInstance();
        this.lastBreak = -1;
        this.chunksPerTick = this.server.getConfig().getChunkSending().getPerTick();
        this.spawnThreshold = this.server.getConfig().getChunkSending().getSpawnThreshold();
        this.spawnLocation = null;
        this.playerData.setGamemode(this.server.getGamemode());
        this.viewDistance = this.server.getViewDistance();
        //this.newPosition = new Vector3(0, 0, 0);
        this.boundingBox = new SimpleAxisAlignedBB(0, 0, 0, 0, 0, 0);
        this.lastSkinChange = -1;

        this.loginChainData = chainData;

        this.randomClientId = chainData.getClientId();
        this.identity = chainData.getClientUUID();
        this.username = TextFormat.clean(chainData.getUsername());
        this.iusername = username.toLowerCase();
        this.setDisplayName(this.username);
        this.setNameTag(this.username);

        this.creationTime = System.currentTimeMillis();
    }

    public int getStartActionTick() {
        return startAction;
    }

    public void startAction() {
        this.startAction = this.server.getTick();
    }

    public void stopAction() {
        this.startAction = -1;
    }

    public int getLastEnderPearlThrowingTick() {
        return lastEnderPearl;
    }

    public void onThrowEnderPearl() {
        this.lastEnderPearl = this.server.getTick();
    }

    public int getLastChorusFruitTeleport() {
        return lastChorusFruitTeleport;
    }

    public void onChorusFruitTeleport() {
        this.lastChorusFruitTeleport = this.server.getTick();
    }

    @Override
    public EnderChest getViewingEnderChest() {
        return viewingEnderChest;
    }

    @Override
    public void setViewingEnderChest(EnderChest chest) {
        if (chest == null && this.viewingEnderChest != null) {
            this.viewingEnderChest.getInventory().getViewers().remove(this);
        } else if (chest != null) {
            ((EnderChestBlockEntity) chest).getInventory().getViewers().add(this);
        }
        this.viewingEnderChest = chest;
    }

    @Override
    public CloudCraftingGrid getCraftingInventory() {
        return invManager.getCraftingGrid();
    }

    public TranslationContainer getLeaveMessage() {
        return new TranslationContainer(TextFormat.YELLOW + "%multiplayer.player.left", this.getDisplayName());
    }

    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * This might disappear in the future.
     * Please use getUniqueId() instead (IP + clientId + name combo, in the future it'll change to real UUID for online auth)
     *
     * @return random client id
     */
    @Deprecated
    public Long getClientId() {
        return randomClientId;
    }

    @Override
    public boolean isBanned() {
        return this.server.isBanned(this);
    }

    @Override
    public void setBanned(boolean value) {
        if (value) {
            this.server.ban(this);
            this.kick(PlayerKickEvent.Reason.NAME_BANNED, "Banned by admin");
        } else {
            this.server.unban(this);
        }
    }

    @Override
    public boolean isWhitelisted() {
        return this.server.isWhitelisted(this.getName().toLowerCase());
    }

    @Override
    public void setWhitelisted(boolean value) {
        if (value) {
            this.server.addWhitelist(this);
        } else {
            this.server.removeWhitelist(this);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    private static boolean hasSubstantiallyMoved(Vector3f oldPos, Vector3f newPos) {
        return oldPos.getFloorX() >> 4 != newPos.getFloorX() >> 4 || oldPos.getFloorZ() >> 4 != newPos.getFloorZ() >> 4;
    }

    @Override
    public OptionalLong getFirstPlayed() {
        return this.playerData.getFirstPlayed();
    }

    public CloudAdventureSettings getAdventureSettings() {
        return adventureSettings;
    }

    public void setAdventureSettings(CloudAdventureSettings adventureSettings) {
        this.adventureSettings = adventureSettings.clone(this);
        this.adventureSettings.update();
    }

    public void resetInAirTicks() {
        this.inAirTicks = 0;
    }

    @Deprecated
    public void setAllowFlight(boolean value) {
        this.getAdventureSettings().set(AdventureSetting.ALLOW_FLIGHT, value);
        this.getAdventureSettings().update();
    }

    @Deprecated
    public boolean getAllowFlight() {
        return this.getAdventureSettings().get(AdventureSetting.ALLOW_FLIGHT);
    }

    public void setAllowModifyWorld(boolean value) {
        this.getAdventureSettings().set(AdventureSetting.WORLD_IMMUTABLE, !value);
        this.getAdventureSettings().set(AdventureSetting.BUILD, value);
        this.getAdventureSettings().set(AdventureSetting.MINE, value);
        this.getAdventureSettings().set(AdventureSetting.WORLD_BUILDER, value);
        this.getAdventureSettings().update();
    }

    public void setAllowInteract(boolean value) {
        setAllowInteract(value, value);
    }

    public void setAllowInteract(boolean value, boolean containers) {
        this.getAdventureSettings().set(AdventureSetting.WORLD_IMMUTABLE, !value);
        this.getAdventureSettings().set(AdventureSetting.DOORS_AND_SWITCHES, value);
        this.getAdventureSettings().set(AdventureSetting.OPEN_CONTAINERS, containers);
        this.getAdventureSettings().update();
    }

    @Deprecated
    public void setAutoJump(boolean value) {
        this.getAdventureSettings().set(AdventureSetting.AUTO_JUMP, value);
        this.getAdventureSettings().update();
    }

    @Deprecated
    public boolean hasAutoJump() {
        return this.getAdventureSettings().get(AdventureSetting.AUTO_JUMP);
    }

    @Override
    public OptionalLong getLastPlayed() {
        return this.playerData.getLastPlayed();
    }

    @Override
    public CloudServer getServer() {
        return (CloudServer) this.server;
    }

    public boolean getRemoveFormat() {
        return removeFormat;
    }

    public void setRemoveFormat() {
        this.setRemoveFormat(true);
    }

    public void setRemoveFormat(boolean remove) {
        this.removeFormat = remove;
    }

    @Override
    public boolean hasPlayedBefore() {
        return this.playerData.getFirstPlayed().getAsLong() > 0;
    }

    public boolean canSee(CloudPlayer player) {
        return !this.hiddenPlayers.containsKey(player.getServerId());
    }

    public void hidePlayer(CloudPlayer player) {
        if (this == player) {
            return;
        }
        this.hiddenPlayers.put(player.getServerId(), player);
        player.despawnFrom(this);
    }

    @Override
    public boolean canCollideWith(Entity entity) {
        return false;
    }

    @Override
    public void spawnTo(CloudPlayer player) {
        if (this.spawned && player.spawned && this.isAlive() && player.isAlive() &&
                player.getLevel() == this.getLevel() && player.canSee(this) && !this.isSpectator()) {
            super.spawnTo(player);
        }
    }

    @Override
    protected BedrockPacket createAddEntityPacket() {
        AddPlayerPacket packet = new AddPlayerPacket();
        packet.setUuid(this.getServerId());
        packet.setUsername(this.getName());
        packet.setUniqueEntityId(this.getUniqueId());
        packet.setRuntimeEntityId(this.getRuntimeId());
        packet.setPosition(this.getPosition());
        packet.setMotion(this.getMotion());
        packet.setRotation(Vector3f.from(this.getPitch(), this.getYaw(), this.getYaw()));
        packet.setHand(this.getInventory().getItemInHand().getNetworkData());
        packet.setPlatformChatId("");
        packet.setDeviceId("");
        packet.getAdventureSettings().setCommandPermission((this.isOp() ? CommandPermission.OPERATOR : CommandPermission.NORMAL));
        packet.getAdventureSettings().setPlayerPermission((this.isOp() ? PlayerPermission.OPERATOR : PlayerPermission.MEMBER));
        this.getData().putAllIn(packet.getMetadata());
        return packet;
    }

    @Override
    public boolean isOnline() {
        return this.connected && this.loggedIn;
    }

    @Override
    public boolean isOp() {
        return this.server.isOp(this);
    }

    @Override
    public void setOp(boolean value) {
        if (value == this.isOp()) {
            return;
        }

        if (value) {
            this.server.addOp(this);
        } else {
            this.server.removeOp(this);
        }

        this.recalculatePermissions();
        this.getAdventureSettings().update();
        this.sendCommandData();
    }

    @Override
    public boolean isSpawned() {
        return this.spawned;
    }

    @Override
    public boolean isPermissionSet(String name) {
        return this.perm.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission permission) {
        return this.perm.isPermissionSet(permission);
    }

    @Override
    public boolean hasPermission(String name) {
        return this.perm != null && this.perm.hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return this.perm.hasPermission(permission);
    }

    @Override
    public PermissionAttachment addAttachment(PluginContainer plugin) {
        return this.addAttachment(plugin, null);
    }

    @Override
    public PermissionAttachment addAttachment(PluginContainer plugin, String name) {
        return this.addAttachment(plugin, name, null);
    }

    @Override
    public PermissionAttachment addAttachment(PluginContainer plugin, String name, Boolean value) {
        return this.perm.addAttachment(plugin, name, value);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        this.perm.removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        this.server.getPermissionManager().unsubscribeFromPermission(CloudServer.BROADCAST_CHANNEL_USERS, this);
        this.server.getPermissionManager().unsubscribeFromPermission(CloudServer.BROADCAST_CHANNEL_ADMINISTRATIVE, this);

        if (this.perm == null) {
            return;
        }

        this.perm.recalculatePermissions();

        if (this.hasPermission(CloudServer.BROADCAST_CHANNEL_USERS)) {
            this.server.getPermissionManager().subscribeToPermission(CloudServer.BROADCAST_CHANNEL_USERS, this);
        }

        if (this.hasPermission(CloudServer.BROADCAST_CHANNEL_ADMINISTRATIVE)) {
            this.server.getPermissionManager().subscribeToPermission(CloudServer.BROADCAST_CHANNEL_ADMINISTRATIVE, this);
        }

        if (this.isEnableClientCommand() && spawned) this.sendCommandData();
    }

    public boolean isEnableClientCommand() {
        return this.enableClientCommand;
    }

    @Override
    public void resetFallDistance() {
        super.resetFallDistance();
        if (this.inAirTicks != 0) {
            this.startAirTicks = 5;
        }
        this.inAirTicks = 0;
        this.highestPosition = this.getPosition().getY();
    }

    public void setEnableClientCommand(boolean enable) {
        this.enableClientCommand = enable;
        SetCommandsEnabledPacket packet = new SetCommandsEnabledPacket();
        packet.setCommandsEnabled(enable);
        this.sendPacket(packet);
        if (enable) this.sendCommandData();
    }

    @Override
    public Map<String, PermissionAttachmentInfo> getEffectivePermissions() {
        return this.perm.getEffectivePermissions();
    }

    public void showPlayer(CloudPlayer player) {
        if (this == player) {
            return;
        }
        this.hiddenPlayers.remove(player.getServerId());
        if (player.isOnline()) {
            player.spawnTo(this);
        }
    }

    @Override
    protected void initEntity() {
        super.initEntity();

        this.addDefaultWindows();
    }

    public boolean isPlayer() {
        return true;
    }

    public void sendCommandData() {
        this.sendPacket(CommandRegistry.get().createPacketFor(this));
    }

    public void removeAchievement(String achievementId) {
        this.playerData.getAchievements().remove(achievementId);
    }

    public boolean isConnected() {
        return connected;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        if (this.spawned) {
            this.getServer().updatePlayerListData(this.getServerId(), this.getUniqueId(), this.getDisplayName(), this.getSerializedSkin(), this.getXuid());
        }
    }

    public boolean hasAchievement(String achievementId) {
        return this.playerData.getAchievements().contains(achievementId);
    }

    @Override
    public Skin getSkin() {
        return this.loginChainData.getSkin();
    }

    @Override
    public void setSkin(Skin skin) {
        this.loginChainData.setSkin(skin);
        if (this.spawned) {
            this.getServer().updatePlayerListData(this.getServerId(), this.getUniqueId(), this.getDisplayName(), this.getSerializedSkin(), this.getXuid());
        }
    }

    public SerializedSkin getSerializedSkin() {
        return ((ClientChainData) this.loginChainData).getSerializedSkin();
    }

    public void setSkin(SerializedSkin skin) {
        ((ClientChainData) this.loginChainData).setSkin(skin);
        super.setSkin(this.getSkin());
        if (this.spawned) {
            this.getServer().updatePlayerListData(this.getServerId(), this.getUniqueId(), this.getDisplayName(), this.getSerializedSkin(), this.getXuid());
        }
    }

    public UUID getServerId() {
        return this.loginChainData.getClientUUID();
    }

    public String getAddress() {
        return this.getSocketAddress().getAddress().getHostAddress();
    }

    public InetSocketAddress getSocketAddress() {
        return this.session.getAddress();
    }

    public int getPort() {
        return this.getSocketAddress().getPort();
    }

    public boolean isSleeping() {
        return this.sleeping != null;
    }

    public int getInAirTicks() {
        return this.inAirTicks;
    }

    public Vector3f getNextPosition() {
        return this.newPosition != null ? this.newPosition : this.getPosition();
    }

    /**
     * Returns whether the player is currently using an item (right-click and hold).
     *
     * @return bool
     */
    public boolean isUsingItem() {
        return this.data.getFlag(USING_ITEM) && this.startAction > -1;
    }

    public String getButtonText() {
        return this.buttonText;
    }

    public void setUsingItem(boolean value) {
        this.startAction = value ? this.server.getTick() : -1;
        this.data.setFlag(USING_ITEM, value);
    }

    public void setButtonText(String text) {
        if (!text.equals(buttonText)) {
            this.buttonText = text;
            this.data.setString(INTERACTIVE_TAG, this.buttonText);
        }
    }

    public Location getSpawn() {
        if (this.spawnLocation != null && this.spawnLocation.getLevel() != null) {
            return this.spawnLocation;
        } else {
            return this.getServer().getDefaultLevel().getSafeSpawn();
        }
    }

    private static int distance(int centerX, int centerZ, int x, int z) {
        int dx = centerX - x;
        int dz = centerZ - z;
        return dx * dx + dz * dz;
    }

    public void setSpawn(Location location) {
        checkNotNull(location, "location");
        this.spawnLocation = location;
        SetSpawnPositionPacket packet = new SetSpawnPositionPacket();
        packet.setSpawnType(SetSpawnPositionPacket.Type.PLAYER_SPAWN);
        packet.setBlockPosition(this.spawnLocation.getPosition().toInt());
        this.sendPacket(packet);
    }

    protected void doFirstSpawn() {
        this.spawned = true;

        this.setEnableClientCommand(true);

        this.getAdventureSettings().update();

        this.sendPotionEffects(this);
        this.sendData(this);
        this.getInventory().sendContents(this);
        this.getInventory().sendArmorContents(this);
        this.getInventory().sendOffHandContents(this);

        SetTimePacket setTimePacket = new SetTimePacket();
        setTimePacket.setTime(this.getLevel().getTime());
        this.sendPacket(setTimePacket);

        Location loc = this.getLevel().getSafeSpawn(this.getLocation());

        PlayerRespawnEvent respawnEvent = new PlayerRespawnEvent(this, loc, true);

        this.server.getEventManager().fire(respawnEvent);

        loc = respawnEvent.getRespawnLocation();

        if (this.getHealth() <= 0) {
            loc = this.getSpawn();

            RespawnPacket respawnPacket = new RespawnPacket();
            respawnPacket.setPosition(loc.getPosition());
            respawnPacket.setState(RespawnPacket.State.SERVER_SEARCHING);
            this.sendPacket(respawnPacket);
        }

        this.sendPlayStatus(PlayStatusPacket.Status.PLAYER_SPAWN);

        this.noDamageTicks = 60;

        this.getServer().sendRecipeList(this);
        getInventory().sendCreativeContents();

        this.getChunkManager().getLoadedChunks().forEach((LongConsumer) chunkKey -> {
            int chunkX = CloudChunk.fromKeyX(chunkKey);
            int chunkZ = CloudChunk.fromKeyZ(chunkKey);
            for (Entity entity : this.getLevel().getLoadedChunkEntities(chunkX, chunkZ)) {
                if (this != entity && !entity.isClosed() && entity.isAlive()) {
                    entity.spawnTo(this);
                }
            }
        });

        int experience = this.getExperience();
        if (experience != 0) {
            this.sendExperience(experience);
        }

        int level = this.getExperienceLevel();
        if (level != 0) {
            this.sendExperienceLevel(this.getExperienceLevel());
        }

        this.teleport(loc, null); // Prevent PlayerTeleportEvent during player spawn

        if (!this.isSpectator()) {
            this.spawnToAll();
        }

        //todo Updater

        //Weather
        if (this.getLevel().isRaining() || this.getLevel().isThundering()) {
            this.getLevel().sendWeather(this);
        }

        //FoodLevel
        PlayerFood food = this.getFoodData();
        if (food.getLevel() != food.getMaxLevel()) {
            food.sendFoodLevel();
        }
    }

    public long getPing() {
        return this.session.getLatency();
    }

    public boolean sleepOn(Vector3i pos) {
        if (!this.isOnline()) {
            return false;
        }

        for (Entity p : this.getLevel().getNearbyEntities(this.boundingBox.grow(2, 1, 2), this)) {
            if (p instanceof CloudPlayer) {
                if (((CloudPlayer) p).sleeping != null && pos.distance(((CloudPlayer) p).sleeping) <= 0.1) {
                    return false;
                }
            }
        }

        PlayerBedEnterEvent ev;
        this.server.getEventManager().fire(ev = new PlayerBedEnterEvent(this, this.getLevel().getBlock(pos)));
        if (ev.isCancelled()) {
            return false;
        }

        this.sleeping = pos.clone();
        this.teleport(Location.from(pos.toFloat().add(0.5, 0.5, 0.5), this.getYaw(), this.getPitch(), this.getLevel()), null);

        this.data.setVector3i(BED_POSITION, pos);
        //this.data.setBoolean(CAN_START_SLEEP, true); todo what did this change to?

        this.setSpawn(Location.from(pos.toFloat(), this.getLevel()));

        this.getLevel().sleepTicks = 60;

        return true;
    }

    public void stopSleep() {
        if (this.sleeping != null) {
            this.server.getEventManager().fire(new PlayerBedLeaveEvent(this, this.getLevel().getBlock(this.sleeping)));

            this.sleeping = null;
            this.data.setVector3i(BED_POSITION, Vector3i.ZERO);
            //this.data.setBoolean(CAN_START_SLEEP, false); // TODO what did this change to?


            this.getLevel().sleepTicks = 0;

            AnimatePacket pk = new AnimatePacket();
            pk.setRuntimeEntityId(this.getRuntimeId());
            pk.setAction(AnimatePacket.Action.WAKE_UP);
            this.sendPacket(pk);
        }
    }

    public boolean awardAchievement(String achievementId) {
        if (!CloudServer.getInstance().getConfig().isAchievements()) {
            return false;
        }

        Achievement achievement = Achievement.achievements.get(achievementId);

        if (achievement == null || hasAchievement(achievementId)) {
            return false;
        }

        for (String id : achievement.requires) {
            if (!this.hasAchievement(id)) {
                return false;
            }
        }
        PlayerAchievementAwardedEvent event = new PlayerAchievementAwardedEvent(this, achievementId);
        this.server.getEventManager().fire(event);

        if (event.isCancelled()) {
            return false;
        }

        this.playerData.getAchievements().add(achievementId);
        achievement.broadcast(this);
        return true;
    }

    public GameMode getGamemode() {
        return this.playerData.getGamemode();
    }

    public boolean setGamemode(GameMode gamemode) {
        return this.setGamemode(gamemode, false);
    }

    /**
     * 0 is true
     * -1 is false
     * other is identifer
     *
     * @param packet packet to send
     * @return packet successfully sent
     */
    public boolean sendPacket(BedrockPacket packet) {
        if (!this.connected) {
            return false;
        }

        PlayerPacketSendEvent event = new PlayerPacketSendEvent(this, packet);
        this.server.getEventManager().fire(event);
        if (event.isCancelled()) {
            return false;
        }

        if (log.isTraceEnabled() && !this.getServer().isIgnoredPacket(packet.getClass())) {
            log.trace("Outbound {}: {}", this.getName(), packet);
        }

        sendPacketInternal(packet);
        return true;
    }

    public void sendPacketInternal(BedrockPacket packet) {
        try (Timing ignored = Timings.getSendDataPacketTiming(packet).startTiming()) {
            this.session.sendPacket(packet);
        }
    }

    @Deprecated
    public void sendSettings() {
        this.getAdventureSettings().update();
    }

    public boolean isSurvival() {
        return this.getGamemode() == GameMode.SURVIVAL;
    }

    public boolean isCreative() {
        return this.getGamemode() == GameMode.CREATIVE;
    }

    public boolean isSpectator() {
        return this.getGamemode() == GameMode.SPECTATOR;
    }

    public boolean isAdventure() {
        return this.getGamemode() == GameMode.ADVENTURE;
    }

    @Override
    public ItemStack[] getDrops() {
        if (!this.isCreative()) {
            return super.getDrops();
        }

        return new ItemStack[0];
    }

    /**
     * 0 is true
     * -1 is false
     * other is identifer
     *
     * @param packet packet to send
     * @return packet successfully sent
     */
    public boolean sendPacketImmediately(BedrockPacket packet) {
        if (!this.connected) {
            return false;
        }

        try (Timing ignored = Timings.getSendDataPacketTiming(packet).startTiming()) {
            this.session.sendPacketImmediately(packet);
        }
        return true;
    }

    @Override
    protected void checkBlockCollision() {
        boolean portal = false;

        for (Block block : this.getCollisionBlocks()) {
            var state = block.getState();
            if (state.getType() == BlockTypes.PORTAL) {
                portal = true;
                continue;
            }

            state.getBehavior().onEntityCollide(block, this);
        }

        if (portal) {
            if (this.isCreative() && this.inPortalTicks < 80) {
                this.inPortalTicks = 80;
            } else {
                this.inPortalTicks++;
            }
        } else {
            this.inPortalTicks = 0;
        }
    }

    protected void checkNearEntities() {
        for (Entity entity : this.getLevel().getNearbyEntities(this.boundingBox.grow(1, 0.5f, 1), this)) {
            this.getLevel().scheduleEntityUpdate(entity);

            if (!entity.isAlive() || !this.isAlive()) {
                continue;
            }

            this.pickupEntity(entity, true);
        }
    }

    public boolean setGamemode(GameMode gamemode, boolean clientSide) {
        if (this.getGamemode() == gamemode) {
            return false;
        }

        PlayerGameModeChangeEvent ev;
        this.server.getEventManager().fire(ev = new PlayerGameModeChangeEvent(this, gamemode));

        if (ev.isCancelled()) {
            return false;
        }

        this.playerData.setGamemode(gamemode);

        if (this.isSpectator()) {
            this.keepMovement = true;
            this.despawnFromAll();
        } else {
            this.keepMovement = false;
            this.spawnToAll();
        }

        this.playerData.setGamemode(this.getGamemode());

        if (!clientSide) {
            SetPlayerGameTypePacket pk = new SetPlayerGameTypePacket();
            pk.setGamemode(gamemode.getVanillaId());
            this.sendPacket(pk);
        }

        this.setAdventureSettings(new CloudAdventureSettings(this, gamemode.getAdventureSettings()));

        if (this.isSpectator()) {
            this.teleport(this.getPosition().add(0, 0.1, 0));
        }

        this.resetFallDistance();

        this.getInventory().sendContents(this);
        this.getInventory().sendHeldItem(this.hasSpawned);
        this.getInventory().sendOffHandContents(this);
        this.getInventory().sendOffHandContents(this.getViewers());

        this.getInventory().sendCreativeContents();
        return true;
    }

    public boolean canOpenInventory() {
        return !this.inventoryOpen;
    }

    public void openInventory(CloudPlayer who) {
        if (this == who) {
            this.inventoryOpen = true;
        }
    }

    public void closeInventory(CloudPlayer who) {
        if (this == who) {
            this.inventoryOpen = false;
        }
    }

    @Override
    public boolean setMotion(Vector3f motion) {
        if (super.setMotion(motion)) {
            if (this.chunk != null) {
                this.addMotion(this.getMotion());  //Send to others
                SetEntityMotionPacket packet = new SetEntityMotionPacket();
                packet.setRuntimeEntityId(this.getRuntimeId());
                packet.setMotion(motion);
                this.sendPacket(packet);  //Send to self
            }

            if (this.getMotion().getY() > 0) {
                //todo: check this
                this.startAirTicks = (int) ((-(Math.log(this.getGravity() / (this.getGravity() + this.getDrag() * this.getMotion().getY()))) / this.getDrag()) * 2 + 5);
            }

            return true;
        }

        return false;
    }

    public void sendAttributes() {
        UpdateAttributesPacket pk = new UpdateAttributesPacket();
        pk.setRuntimeEntityId(this.getRuntimeId());
        List<AttributeData> attributes = pk.getAttributes();
        attributes.add(NetworkUtils.attributeToNetwork(Attribute.getAttribute(Attribute.MAX_HEALTH).setMaxValue(this.getMaxHealth()).setValue(health > 0 ? (health < getMaxHealth() ? health : getMaxHealth()) : 0)));
        attributes.add(NetworkUtils.attributeToNetwork(Attribute.getAttribute(Attribute.MAX_HUNGER).setValue(this.getFoodData().getLevel())));
        attributes.add(NetworkUtils.attributeToNetwork(Attribute.getAttribute(Attribute.MOVEMENT_SPEED).setValue(this.getMovementSpeed())));
        attributes.add(NetworkUtils.attributeToNetwork(Attribute.getAttribute(Attribute.EXPERIENCE_LEVEL).setValue(this.getExperienceLevel())));
        attributes.add(NetworkUtils.attributeToNetwork(Attribute.getAttribute(Attribute.EXPERIENCE).setValue(((float) this.getExperience()) / calculateRequireExperience(this.getExperienceLevel()))));
        this.sendPacket(pk);
    }

    @Override
    protected void checkGroundState(double movX, double movY, double movZ, double dx, double dy, double dz) {
        if (!this.onGround || movX != 0 || movY != 0 || movZ != 0) {
            boolean onGround = false;

            AxisAlignedBB bb = this.boundingBox.clone();
            bb.setMaxY(bb.getMinY() + 0.5f);
            bb.setMinY(bb.getMinY() - 1);

            AxisAlignedBB realBB = this.boundingBox.clone();
            realBB.setMaxY(realBB.getMinY() + 0.1f);
            realBB.setMinY(realBB.getMinY() - 0.2f);

            int minX = NukkitMath.floorDouble(bb.getMinX());
            int minY = NukkitMath.floorDouble(bb.getMinY());
            int minZ = NukkitMath.floorDouble(bb.getMinZ());
            int maxX = NukkitMath.ceilDouble(bb.getMaxX());
            int maxY = NukkitMath.ceilDouble(bb.getMaxY());
            int maxZ = NukkitMath.ceilDouble(bb.getMaxZ());

            for (int z = minZ; z <= maxZ; ++z) {
                for (int x = minX; x <= maxX; ++x) {
                    for (int y = minY; y <= maxY; ++y) {
                        Block block = this.getLevel().getBlock(x, y, z);
                        var behavior = block.getState().getBehavior();

                        if (!behavior.canPassThrough(block.getState()) && behavior.collidesWithBB(block, realBB)) {
                            onGround = true;
                            break;
                        }
                    }
                }
            }

            this.onGround = onGround;
        }

        this.isCollided = this.onGround;
    }

    public void checkInteractNearby() {
        int interactDistance = isCreative() ? 5 : 3;
        if (canInteract(this.getPosition(), interactDistance)) {
            if (getEntityPlayerLookingAt(interactDistance) != null) {
                Interactable onInteract = getEntityPlayerLookingAt(interactDistance);
                setButtonText(onInteract.getInteractButtonText());
            } else {
                setButtonText("");
            }
        } else {
            setButtonText("");
        }
    }

    protected void processMovement(int tickDiff) {
        if (!this.isAlive() || !this.spawned || this.newPosition == null || this.teleportPosition != null || this.isSleeping()) {
            return;
        }

        Vector3f newPosition = this.newPosition;
        Vector3f currentPos = this.getPosition();
        float distanceSquared = newPosition.distanceSquared(currentPos);

        boolean revert = false;
        if ((distanceSquared / ((float) (tickDiff * tickDiff))) > 100 && (newPosition.getY() - currentPos.getY()) > -5) {
            log.debug("{} moved too fast!", this.getName());
            revert = true;
        } else {
            if (this.chunk == null) {
                CloudChunk chunk = this.getLevel().getLoadedChunk(newPosition);
                if (chunk == null) {
                    revert = true;
                } else {
                    this.chunk = chunk;
                }
            }
        }

        float tdx = newPosition.getX() - currentPos.getX();
        float tdz = newPosition.getZ() - currentPos.getZ();
        double distance = Math.sqrt(tdx * tdx + tdz * tdz);

        if (!revert && distanceSquared != 0) {
            float dx = newPosition.getX() - currentPos.getX();
            float dy = newPosition.getY() - currentPos.getY();
            float dz = newPosition.getZ() - currentPos.getZ();

            this.fastMove(dx, dy, dz);
            if (this.newPosition == null) {
                return; //maybe solve that in better way
            }

            double diffX = currentPos.getX() - newPosition.getX();
            double diffY = currentPos.getY() - newPosition.getY();
            double diffZ = currentPos.getZ() - newPosition.getZ();

            double yS = 0.5 + this.ySize;
            if (diffY >= -yS || diffY <= yS) {
                diffY = 0;
            }

            if (diffX != 0 || diffY != 0 || diffZ != 0) {
                this.position = newPosition;
                float radius = this.getWidth() / 2;
                this.boundingBox.setBounds(this.position.getX() - radius, this.position.getY(), this.position.getZ() - radius,
                        this.position.getX() + radius, this.position.getY() + this.getHeight(), this.position.getZ() + radius);
            }
        }

        Location from = Location.from(this.lastPosition, this.lastYaw, this.lastPitch, this.getLevel());
        Location to = this.getLocation();

        double delta = Math.pow(this.lastPosition.getX() - to.getX(), 2) + Math.pow(this.lastPosition.getY() - to.getY(), 2) + Math.pow(this.position.getZ() - to.getZ(), 2);
        double deltaAngle = Math.abs(this.lastYaw - to.getYaw()) + Math.abs(this.lastPitch - to.getPitch());

        if (!revert && (delta > 0.0001d || deltaAngle > 1d)) {
            boolean isFirst = this.firstMove;

            this.firstMove = false;
            this.lastPosition = to.getPosition();

            this.lastYaw = to.getYaw();
            this.lastPitch = to.getPitch();

            if (!isFirst) {
                List<Block> blocksAround = new ArrayList<>(this.blocksAround);
                List<Block> collidingBlockStates = new ArrayList<>(this.collisionBlockStates);

                PlayerMoveEvent ev = new PlayerMoveEvent(this, from, to);

                this.blocksAround = null;
                this.collisionBlockStates = null;

                this.server.getEventManager().fire(ev);

                if (!(revert = ev.isCancelled())) { //Yes, this is intended
                    if (!to.equals(ev.getTo())) { //If plugins modify the destination
                        this.teleport(ev.getTo(), null);
                    } else {
                        this.addMovement(this.getX(), this.getY() + getBaseOffset(), this.getZ(), this.getYaw(), this.getPitch(), this.getYaw());
                    }
                } else {
                    this.blocksAround = blocksAround;
                    this.collisionBlockStates = collidingBlockStates;
                }
            }

            this.speed = from.getPosition().min(to.getPosition());
        } else {
            this.speed = Vector3f.ZERO;
        }

        if (!revert && (this.isFoodEnabled() || this.getServer().getDifficulty() == Difficulty.PEACEFUL)) {
            if ((this.isSurvival() || this.isAdventure())/* && !this.getRiddingOn() instanceof Entity*/) {

                //UpdateFoodExpLevel
                if (distance >= 0.05) {
                    double jump = 0;
                    double swimming = this.isInsideOfWater() ? 0.015 * distance : 0;
                    if (swimming != 0) distance = 0;
                    if (this.isSprinting()) {  //Running
                        if (this.inAirTicks == 3 && swimming == 0) {
                            jump = 0.7;
                        }
                        this.getFoodData().updateFoodExpLevel(0.06 * distance + jump + swimming);
                    } else {
                        if (this.inAirTicks == 3 && swimming == 0) {
                            jump = 0.2;
                        }
                        this.getFoodData().updateFoodExpLevel(0.01 * distance + jump + swimming);
                    }
                }
            }
        }

        if (revert) {

            this.lastPosition = from.getPosition();

            this.lastYaw = from.getYaw();
            this.lastPitch = from.getPitch();

            // We have to send slightly above otherwise the player will fall into the ground.
            Location location = from.add(0, 0.00001, 0);
            log.debug("processMovement REVERTING");
            this.sendPosition(location.getPosition(), from.getYaw(), from.getPitch(), MovePlayerPacket.Mode.RESPAWN); // TODO Check this
            //this.sendSettings();
            this.forceMovement = location.getPosition();
        } else {
            this.forceMovement = null;
        }

        this.newPosition = null;
    }

    @Override
    public void addMovement(double x, double y, double z, double yaw, double pitch, double headYaw) {
        this.sendPosition(Vector3f.from(x, y - getBaseOffset() /*TODO: find better solution */, z), yaw, pitch, MovePlayerPacket.Mode.NORMAL, getViewers());
    }

    @Override
    public boolean onUpdate(int currentTick) {
        this.packetsRecieved = 0;

        if (!this.loggedIn) {
            return false;
        }

        int tickDiff = currentTick - this.lastUpdate;

        if (tickDiff <= 0) {
            return true;
        }

        this.messageCounter = 2;

        this.lastUpdate = currentTick;

        try (Timing ignored = this.timing.startTiming()) {
            if (this.fishing != null && this.server.getTick() % 20 == 0) {
                if (this.getPosition().distance(fishing.getPosition()) > 33) {
                    this.stopFishing(false);
                }
            }

            if (!this.isAlive() && this.spawned) {
                ++this.deadTicks;
                if (this.deadTicks >= 10) {
                    this.despawnFromAll();
                }
                return true;
            }

            if (this.spawned) {
                this.getChunkManager().queueNewChunks();
                this.processMovement(tickDiff);

                if (!this.isSpectator()) {
                    this.checkNearEntities();
                }

                this.entityBaseTick(tickDiff);

                if (this.getServer().getDifficulty() == Difficulty.PEACEFUL && this.getLevel().getGameRules().get(GameRules.NATURAL_REGENERATION)) {
                    if (this.getHealth() < this.getMaxHealth() && this.ticksLived % 20 == 0) {
                        this.heal(1);
                    }

                    PlayerFood foodData = this.getFoodData();

                    if (foodData.getLevel() < 20 && this.ticksLived % 10 == 0) {
                        foodData.addFoodLevel(1, 0);
                    }
                }

                if (this.isOnFire() && this.lastUpdate % 10 == 0) {
                    if (this.isCreative() && !this.isInsideOfFire()) {
                        this.extinguish();
                    } else if (this.getLevel().isRaining()) {
                        if (this.getLevel().canBlockSeeSky(this.getPosition())) {
                            this.extinguish();
                        }
                    }
                }

                if (!this.isSpectator() && this.speed != null) {
                    if (this.onGround) {
                        if (this.inAirTicks != 0) {
                            this.startAirTicks = 5;
                        }
                        this.inAirTicks = 0;
                        this.highestPosition = this.getPosition().getY();
                    } else {
                        float curY = this.getPosition().getY();
                        if (curY > highestPosition) {
                            this.highestPosition = curY;
                        }

                        if (this.isGliding()) this.resetFallDistance();

                        ++this.inAirTicks;

                    }

                    if (this.isSurvival() || this.isAdventure()) {
                        if (this.getFoodData() != null) this.getFoodData().update(tickDiff);
                    }
                }
            }

            this.checkTeleportPosition();

            if (currentTick % 10 == 0) {
                this.checkInteractNearby();
            }

            if (this.spawned && this.dummyBossBars.size() > 0 && currentTick % 100 == 0) {
                this.dummyBossBars.values().forEach(DummyBossBar::updateBossEntityPosition);
            }

            this.data.update();
        }

        return true;
    }

    /**
     * Returns the Entity the player is looking at currently
     *
     * @param maxDistance the maximum distance to check for entities
     * @return Entity|null    either NULL if no entity is found or an instance of the entity
     */
    public Interactable getEntityPlayerLookingAt(int maxDistance) {
        try (Timing ignored = Timings.playerEntityLookingAtTimer.startTiming()) {
            Interactable entity = null;

            Set<Entity> nearbyEntities = this.getLevel().getNearbyEntities(boundingBox.grow(maxDistance, maxDistance, maxDistance), this);

            // get all blocks in looking direction until the max interact distance is reached (it's possible that startblock isn't found!)

            Vector3f position = this.getPosition().add(0, getEyeHeight(), 0);
            for (Vector3i pos : BlockRayTrace.of(position, getDirectionVector(), maxDistance)) {
                Block block = this.getLevel().getLoadedBlock(pos);
                if (block == null) {
                    break;
                }

                entity = getEntityAtPosition(nearbyEntities, pos.getX(), pos.getY(), pos.getZ());

                if (entity != null) {
                    break;
                }
            }
            return entity;
        }
    }

    public boolean canInteract(Vector3f pos, double maxDistance) {
        return this.canInteract(pos, maxDistance, 6.0);
    }

    public boolean canInteract(Vector3f pos, double maxDistance, double maxDiff) {
        if (this.getPosition().distanceSquared(pos) > maxDistance * maxDistance) {
            return false;
        }

        Vector2f dV = this.getDirectionPlane();
        double dot = dV.dot(this.getPosition().toVector2(true));
        double dot1 = dV.dot(pos.toVector2(true));
        return (dot1 - dot) >= -maxDiff;
    }

    private Interactable getEntityAtPosition(Set<Entity> nearbyEntities, int x, int y, int z) {
        try (Timing ignored = Timings.playerEntityAtPositionTimer.startTiming()) {
            for (Entity nearestEntity : nearbyEntities) {
                Vector3f position = nearestEntity.getPosition();
                if (position.getFloorX() == x && position.getFloorY() == y && position.getFloorZ() == z
                        && nearestEntity instanceof Interactable
                        && ((Interactable) nearestEntity).canDoInteraction()) {
                    return (Interactable) nearestEntity;
                }
            }
            return null;
        }
    }

    public void completeLoginSequence() {
        PlayerLoginEvent ev;
        this.server.getEventManager().fire(ev = new PlayerLoginEvent(this, "Plugin reason"));
        if (ev.isCancelled()) {
            this.close(this.getLeaveMessage(), ev.getKickMessage());
            return;
        }

        Vector3f pos = this.getPosition();

        StartGamePacket startGamePacket = new StartGamePacket();
        startGamePacket.setUniqueEntityId(this.getUniqueId());
        startGamePacket.setRuntimeEntityId(this.getRuntimeId());
        startGamePacket.setPlayerGameType(GameType.from(this.getGamemode().getVanillaId()));
        startGamePacket.setPlayerPosition(pos);
        startGamePacket.setRotation(Vector2f.from(this.getYaw(), this.getPitch()));
        startGamePacket.setSeed(-1L);
        startGamePacket.setDimensionId(0);
        startGamePacket.setTrustingPlayers(false);
        startGamePacket.setLevelGameType(GameType.from(this.getGamemode().getVanillaId()));
        startGamePacket.setDifficulty(this.server.getDifficulty().ordinal());
        startGamePacket.setDefaultSpawn(this.getSpawn().getPosition().toInt());
        startGamePacket.setAchievementsDisabled(true);
        startGamePacket.setDayCycleStopTime(this.getLevel().getTime());
        startGamePacket.setRainLevel(0);
        startGamePacket.setLightningLevel(0);
        startGamePacket.setCommandsEnabled(this.isEnableClientCommand());
        startGamePacket.setMultiplayerGame(true);
        startGamePacket.setBroadcastingToLan(true);
        NetworkUtils.gameRulesToNetwork(this.getLevel().getGameRules(), startGamePacket.getGamerules());
        startGamePacket.setLevelId(""); // This is irrelevant since we have multiple levels
        startGamePacket.setLevelName(this.getServer().getNetwork().getName()); // We might as well use the MOTD instead of the default level name
        startGamePacket.setGeneratorId(1); // 0 old, 1 infinite, 2 flat - Has no effect to my knowledge
        startGamePacket.setItemEntries(CloudItemRegistry.get().getItemEntries());
        startGamePacket.setXblBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        startGamePacket.setServerChunkTickRange(4);
        startGamePacket.setVanillaVersion("1.17.40"); // Temporary hack that allows player to join by disabling the new chunk columns introduced in update 1.18
        startGamePacket.setPremiumWorldTemplateId("");
        startGamePacket.setMultiplayerCorrelationId("");
        startGamePacket.setInventoriesServerAuthoritative(true);
        SyncedPlayerMovementSettings settings = new SyncedPlayerMovementSettings();
        settings.setMovementMode(AuthoritativeMovementMode.CLIENT);
        settings.setRewindHistorySize(0);
        settings.setServerAuthoritativeBlockBreaking(false);
        startGamePacket.setPlayerMovementSettings(settings);
        startGamePacket.setServerEngine("");
        startGamePacket.setPlayerPropertyData(NbtMap.EMPTY);
        startGamePacket.setWorldTemplateId(new UUID(0, 0));
        startGamePacket.setWorldEditor(false);
        startGamePacket.setChatRestrictionLevel(ChatRestrictionLevel.NONE);
        this.sendPacket(startGamePacket);

        BiomeDefinitionListPacket biomeDefinitionListPacket = new BiomeDefinitionListPacket();
        biomeDefinitionListPacket.setDefinitions(Biome.BIOME_DEFINITIONS);
        this.sendPacket(biomeDefinitionListPacket);

        AvailableEntityIdentifiersPacket availableEntityIdentifiersPacket = new AvailableEntityIdentifiersPacket();
        availableEntityIdentifiersPacket.setIdentifiers(EntityRegistry.get().getEntityIdentifiersPalette());
        this.sendPacket(availableEntityIdentifiersPacket);

//        UpdateBlockPropertiesPacket updateBlockPropertiesPacket = new UpdateBlockPropertiesPacket();
//        updateBlockPropertiesPacket.setProperties(BlockRegistry.get().getPropertiesTag());
//        this.sendPacket(updateBlockPropertiesPacket);

        this.loggedIn = true;

        this.getLevel().sendTime(this);

        this.setMovementSpeed(DEFAULT_SPEED);
        this.sendAttributes();
        this.setNameTagVisible(true);
        this.setNameTagAlwaysVisible(true);
        this.setCanClimb(true);

        log.info(this.getServer().getLanguage().translate("cloudburst.player.logIn",
                TextFormat.AQUA + this.username + TextFormat.WHITE,
                this.getAddress(),
                this.getPort(),
                this.getUniqueId(),
                this.getLevel().getName(),
                NukkitMath.round(pos.getX(), 4),
                NukkitMath.round(pos.getY(), 4),
                NukkitMath.round(pos.getZ(), 4)
        ));

        if (this.isOp() || this.hasPermission("cloudburst.textcolor")) {
            this.setRemoveFormat(false);
        }

        this.server.addOnlinePlayer(this);
        this.server.onPlayerCompleteLoginSequence(this);
    }

    public void checkNetwork() {
        if (!this.isConnected()) {
            return;
        }

        try (Timing ignore = Timings.playerNetworkReceiveTimer.startTiming()) {
            BedrockPacket packet;
            while ((packet = this.inboundQueue.poll()) != null) {
                packetHandler.handle(packet);
            }
        }

        if (!this.loggedIn) {
            return;
        }

        this.getChunkManager().sendQueued();

        if (this.getChunkManager().getChunksSent() >= this.spawnThreshold && !this.spawned && this.teleportPosition == null) {
            this.doFirstSpawn();
        }
    }

    public void processLogin() {
        if (this.server.getOnlinePlayers().size() >= this.server.getMaxPlayers() && this.kick(PlayerKickEvent.Reason.SERVER_FULL, "disconnectionScreen.serverFull", false)) {
            return;
        } else if (!this.server.isWhitelisted(this)) {
            this.kick(PlayerKickEvent.Reason.NOT_WHITELISTED, "Server is white-listed");
            return;
        } else if (this.isBanned()) {
            this.kick(PlayerKickEvent.Reason.NAME_BANNED, "You are banned");
            return;
        } else if (this.server.isIPBanned(this)) {
            this.kick(PlayerKickEvent.Reason.IP_BANNED, "You are banned");
            return;
        }

        if (this.hasPermission(CloudServer.BROADCAST_CHANNEL_USERS)) {
            this.server.getPermissionManager().subscribeToPermission(CloudServer.BROADCAST_CHANNEL_USERS, this);
        }
        if (this.hasPermission(CloudServer.BROADCAST_CHANNEL_ADMINISTRATIVE)) {
            this.server.getPermissionManager().subscribeToPermission(CloudServer.BROADCAST_CHANNEL_ADMINISTRATIVE, this);
        }

        CloudPlayer oldPlayer = null;
        for (CloudPlayer p : new ArrayList<>(this.getServer().getOnlinePlayers().values())) {
            if (p != this && p.getName() != null && p.getName().equalsIgnoreCase(this.getName()) ||
                    this.getServerId().equals(p.getServerId())) {
                oldPlayer = p;
                break;
            }
        }
        NbtMap nbt;
        if (oldPlayer != null) {
            NbtMapBuilder tag = NbtMap.builder();
            oldPlayer.saveAdditionalData(tag);
            nbt = tag.build();
            oldPlayer.close("", "disconnectionScreen.loggedinOtherLocation");
        } else {
            File legacyDataFile = new File(server.getDataPath() + "players/" + this.username.toLowerCase() + ".dat");
            File dataFile = new File(server.getDataPath() + "players/" + this.identity.toString() + ".dat");
            if (legacyDataFile.exists() && !dataFile.exists()) {
                nbt = this.server.getOfflinePlayerData(this.username, false);

                if (!legacyDataFile.delete()) {
                    log.warn("Could not delete legacy player data for {}", this.username);
                }
            } else {
                nbt = this.server.getOfflinePlayerData(this.identity, true);
            }
        }

        if (nbt == null) {
            this.close(this.getLeaveMessage(), "Invalid data");
            return;
        }

        if (loginChainData.isXboxAuthed() && server.getConfig().isXboxAuth() || !server.getConfig().isXboxAuth()) {
            server.updateName(this.identity, this.username);
        }

        this.loadAdditionalData(nbt);

        if (this.server.getForceGamemode()) {
            this.playerData.setGamemode(this.server.getGamemode());
        }

        this.adventureSettings = new CloudAdventureSettings(this)
                .set(AdventureSetting.WORLD_IMMUTABLE, isAdventure() || isSpectator())
                .set(AdventureSetting.WORLD_BUILDER, !isAdventure() && !isSpectator())
                .set(AdventureSetting.AUTO_JUMP, true)
                .set(AdventureSetting.ALLOW_FLIGHT, isCreative())
                .set(AdventureSetting.NO_CLIP, isSpectator());

        CloudLevel level;
        if ((level = this.server.getLevelByName(this.playerData.getLevel())) == null || !isAlive()) {
            this.level = this.server.getDefaultLevel();
        } else {
            this.level = level;
        }

        if (this.server.getAutoSave()) {
            this.server.saveOfflinePlayerData(this.identity, nbt, true);
        }

        this.server.onPlayerLogin(this);

        super.init(this.getLocation());

        if (this.isSpectator()) this.keepMovement = true;

        this.forceMovement = this.teleportPosition = this.getPosition();
    }

    /**
     * Sends a chat message as this player. If the message begins with a / (forward-slash) it will be treated
     * as a command.
     *
     * @param message message to send
     * @return successful
     */
    public boolean chat(String message) {
        if (!this.spawned || !this.isAlive()) {
            return false;
        }

        this.invManager.getCraftingGrid().setCraftingGridType(CraftingGrid.Type.CRAFTING_GRID_SMALL);

        if (this.removeFormat) {
            message = TextFormat.clean(message, true);
        }

        for (String msg : message.split("\n")) {
            if (!msg.trim().isEmpty() && msg.length() < 512 && this.messageCounter-- > 0) {
                PlayerChatEvent chatEvent = new PlayerChatEvent(this, msg, "chat.type.text",
                        //TODO this is way to hacky
                        new HashSet<>(this.getServer().getOnlinePlayers().values()));
                this.server.getEventManager().fire(chatEvent);
                if (!chatEvent.isCancelled()) {
                    this.server.broadcastMessage(this.getServer().getLanguage().translate(chatEvent.getFormat(), chatEvent.getPlayer().getDisplayName(), chatEvent.getMessage()), chatEvent.getRecipients());
                }
            }
        }

        return true;
    }

    public boolean kick() {
        return this.kick("");
    }

    public boolean kick(String reason, boolean isAdmin) {
        return this.kick(PlayerKickEvent.Reason.UNKNOWN, reason, isAdmin);
    }

    public boolean kick(String reason) {
        return kick(PlayerKickEvent.Reason.UNKNOWN, reason);
    }

    public boolean kick(PlayerKickEvent.Reason reason) {
        return this.kick(reason, true);
    }

    public boolean kick(PlayerKickEvent.Reason reason, String reasonString) {
        return this.kick(reason, reasonString, true);
    }

    public boolean kick(PlayerKickEvent.Reason reason, boolean isAdmin) {
        return this.kick(reason, reason.toString(), isAdmin);
    }

    public boolean kick(PlayerKickEvent.Reason reason, String reasonString, boolean isAdmin) {
        PlayerKickEvent ev;
        this.server.getEventManager().fire(ev = new PlayerKickEvent(this, reason, this.getLeaveMessage()));
        if (!ev.isCancelled()) {
            String message;
            if (isAdmin) {
                if (!this.isBanned()) {
                    message = "Kicked by admin." + (!reasonString.isEmpty() ? " Reason: " + reasonString : "");
                } else {
                    message = reasonString;
                }
            } else {
                if (reasonString.isEmpty()) {
                    message = "disconnectionScreen.noReason";
                } else {
                    message = reasonString;
                }
            }

            this.close(ev.getQuitMessage(), message);

            return true;
        }

        return false;
    }

    public void handleDataPacket(BedrockPacket packet) {
        this.inboundQueue.offer(packet);
    }

    public int getChunkRadius() {
        return this.getChunkManager().getChunkRadius();
    }

    public String getXuid() {
        return this.getLoginChainData().isXboxAuthed() ? this.getLoginChainData().getXUID() : "";
    }

    @Override
    public void sendMessage(String message) {
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.RAW);
        packet.setXuid(this.getXuid());
        packet.setMessage(this.server.getLanguage().translateOnly("cloudburst.", message));
        this.sendPacket(packet);
    }

    @Override
    public void sendMessage(TextContainer message) {
        if (message instanceof TranslationContainer) {
            this.sendTranslation(message.getText(), ((TranslationContainer) message).getParameters());
            return;
        }
        this.sendMessage(message.getText());
    }

    public void sendTranslation(String message, Object... parameters) {
        if (parameters == null) parameters = new Object[0];
        TextPacket packet = new TextPacket();
        if (!this.server.isLanguageForced()) {
            packet.setType(TextPacket.Type.TRANSLATION);
            packet.setMessage(this.server.getLanguage().translateOnly("cloudburst.", message, parameters));
            String[] params = new String[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                params[i] = this.server.getLanguage().translateOnly("cloudburst.", parameters[i].toString());
            }
            packet.setParameters(Arrays.asList(params));
        } else {
            packet.setType(TextPacket.Type.RAW);
            packet.setMessage(this.server.getLanguage().translate(message, parameters));
        }
        packet.setNeedsTranslation(true);
        packet.setXuid(this.getXuid());
        this.sendPacket(packet);
    }

    public void sendChat(String message) {
        this.sendChat("", message);
    }

    public void sendChat(String source, String message) {
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.CHAT);
        packet.setSourceName(source);
        packet.setMessage(this.server.getLanguage().translateOnly("cloudburst.", message));
        packet.setXuid(this.getXuid());
        this.sendPacket(packet);
    }

    public void sendPopup(String message) {
        this.sendPopup(message, "");
    }

    public void sendPopup(String message, String subtitle) {
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.POPUP);
        packet.setMessage(message);
        packet.setXuid(this.getXuid());
        this.sendPacket(packet);
    }

    public void sendTip(String message) {
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.TIP);
        packet.setMessage(message);
        packet.setXuid(this.getXuid());
        this.sendPacket(packet);
    }

    public void clearTitle() {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.CLEAR);
        packet.setText("");
        this.sendPacket(packet);
    }

    /**
     * Resets both title animation times and subtitle for the next shown title
     */
    public void resetTitleSettings() {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.RESET);
        packet.setText("");
        this.sendPacket(packet);
    }

    public void setSubtitle(String subtitle) {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.SUBTITLE);
        packet.setText(subtitle);
        this.sendPacket(packet);
    }

    public void setTitleAnimationTimes(int fadein, int duration, int fadeout) {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.TIMES);
        packet.setFadeInTime(fadein);
        packet.setStayTime(duration);
        packet.setFadeOutTime(fadeout);
        packet.setText("");
        this.sendPacket(packet);
    }

    private void setTitle(String text) {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.TITLE);
        packet.setText(text);
        this.sendPacket(packet);
    }

    public void sendTitle(String title) {
        this.sendTitle(title, null, 20, 20, 5);
    }

    public void sendTitle(String title, String subtitle) {
        this.sendTitle(title, subtitle, 20, 20, 5);
    }

    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        this.setTitleAnimationTimes(fadeIn, stay, fadeOut);
        if (!Strings.isNullOrEmpty(subtitle)) {
            this.setSubtitle(subtitle);
        }
        // title won't send if an empty string is used.
        this.setTitle(Strings.isNullOrEmpty(title) ? " " : title);
    }

    public void sendActionBar(String title) {
        this.sendActionBar(title, 1, 0, 1);
    }

    public void sendActionBar(String title, int fadein, int duration, int fadeout) {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(SetTitlePacket.Type.ACTIONBAR);
        packet.setText(title);
        packet.setFadeInTime(fadein);
        packet.setStayTime(duration);
        packet.setFadeInTime(fadeout);
        this.sendPacket(packet);
    }

    @Override
    public void close() {
        this.close("");
    }

    public void close(String message) {
        this.close(message, "generic");
    }

    public void close(String message, String reason) {
        this.close(message, reason, true);
    }

    public void close(String message, String reason, boolean notify) {
        this.close(new TextContainer(message), reason, notify);
    }

    public void close(TextContainer message) {
        this.close(message, "generic");
    }

    public void close(TextContainer message, String reason) {
        this.close(message, reason, true);
    }

    public void setChunkRadius(int chunkRadius) {
        this.getChunkManager().setChunkRadius(chunkRadius);
    }

    public void save() {
        this.save(false);
    }

    public PlayerChunkManager getChunkManager() {
        return this.chunkManager;
    }

    public String getName() {
        return this.username;
    }

    public Vector3f getTeleportPosition() {
        return teleportPosition;
    }

    public void setTeleportPosition(Vector3f teleportPosition) {
        this.teleportPosition = teleportPosition;
    }

    public Vector3f getForceMovement() {
        return forceMovement;
    }

    public void setForceMovement(Vector3f forceMovement) {
        this.forceMovement = forceMovement;
    }

    public Vector3f getNewPosition() {
        return newPosition;
    }

    public void setNewPosition(Vector3f newPosition) {
        this.newPosition = newPosition;
    }

    public void close(TextContainer message, String reason, boolean notify) {
        if (this.connected && !this.closed) {
            if (notify && reason.length() > 0) {
                DisconnectPacket packet = new DisconnectPacket();
                packet.setKickMessage(reason);
                this.sendPacketImmediately(packet);
            }

            this.connected = false;
            PlayerQuitEvent ev = null;
            if (this.getName() != null && this.getName().length() > 0) {
                this.server.getEventManager().fire(ev = new PlayerQuitEvent(this, message, true, reason));
                if (this.loggedIn && ev.getAutoSave()) {
                    this.save();
                }
                if (this.fishing != null) {
                    this.stopFishing(false);
                }
            }

            for (CloudPlayer player : new ArrayList<>(this.server.getOnlinePlayers().values())) {
                if (!player.canSee(this)) {
                    player.showPlayer(this);
                }
            }

            this.hiddenPlayers.clear();

            this.removeAllWindows(true);

            this.getChunkManager().getLoadedChunks().forEach((LongConsumer) chunkKey -> {
                int chunkX = CloudChunk.fromKeyX(chunkKey);
                int chunkZ = CloudChunk.fromKeyZ(chunkKey);

                for (Entity entity : this.getLevel().getLoadedChunkEntities(chunkX, chunkZ)) {
                    if (entity != this) {
                        entity.getViewers().remove(this);
                    }
                }
            });

            super.close();

            if (!this.session.isClosed()) {
                this.session.disconnect(notify ? reason : "");
            }

            if (this.loggedIn) {
                this.server.removeOnlinePlayer(this);
            }

            this.loggedIn = false;

            if (ev != null && !Objects.equals(this.username, "") && this.spawned && !Objects.equals(ev.getQuitMessage().toString(), "")) {
                this.server.broadcastMessage(ev.getQuitMessage());
            }

            this.server.getPermissionManager().unsubscribeFromPermission(CloudServer.BROADCAST_CHANNEL_USERS, this);
            this.spawned = false;
            log.info(this.getServer().getLanguage().translate("cloudburst.player.logOut",
                    TextFormat.AQUA + (this.getName() == null ? "" : this.getName()) + TextFormat.WHITE,
                    this.getAddress(),
                    this.getPort(),
                    reason));
            this.windows.clear();
            this.hasSpawned.clear();
            this.spawnLocation = null;

            if (!passengers.isEmpty()) {
                passengers.forEach(entity -> entity.dismount(this));
            }
            if (this.vehicle != null) {
                this.dismount(vehicle);
            }
        }

        if (this.perm != null) {
            this.perm.clearPermissions();
            this.perm = null;
        }

        this.getChunkManager().clear();

        this.chunk = null;

        this.server.removePlayer(this);
    }

    @Override
    public void setHealth(float health) {
        if (health < 1) {
            health = 0;
        }

        super.setHealth(health);
        //TODO: Remove it in future! This a hack to solve the client-side absorption bug! WFT Mojang (Half a yellow heart cannot be shown, we can test it in local gaming)
        Attribute attr = Attribute.getAttribute(Attribute.MAX_HEALTH).setMaxValue(this.getAbsorption() % 2 != 0 ? this.getMaxHealth() + 1 : this.getMaxHealth()).setValue(health > 0 ? (health < getMaxHealth() ? health : getMaxHealth()) : 0);
        if (this.spawned) {
            UpdateAttributesPacket packet = new UpdateAttributesPacket();
            packet.getAttributes().add(NetworkUtils.attributeToNetwork(attr));
            packet.setRuntimeEntityId(this.getRuntimeId());
            this.sendPacket(packet);
        }
    }

    @Override
    public void setMaxHealth(int maxHealth) {
        super.setMaxHealth(maxHealth);

        Attribute attr = Attribute.getAttribute(Attribute.MAX_HEALTH).setMaxValue(this.getAbsorption() % 2 != 0 ? this.getMaxHealth() + 1 : this.getMaxHealth()).setValue(health > 0 ? (health < getMaxHealth() ? health : getMaxHealth()) : 0);
        if (this.spawned) {
            UpdateAttributesPacket packet = new UpdateAttributesPacket();
            packet.getAttributes().add(NetworkUtils.attributeToNetwork(attr));
            packet.setRuntimeEntityId(this.getRuntimeId());
            this.sendPacket(packet);
        }
    }

    public int getExperience() {
        return this.exp;
    }

    public int getExperienceLevel() {
        return this.expLevel;
    }

    public void addExperience(int add) {
        if (add == 0) return;
        int now = this.getExperience();
        int added = now + add;
        int level = this.getExperienceLevel();
        int most = calculateRequireExperience(level);
        while (added >= most) {  //Level Up!
            added = added - most;
            level++;
            most = calculateRequireExperience(level);
        }
        this.setExperience(added, level);
    }

    public static int calculateRequireExperience(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        } else if (level >= 15) {
            return 37 + (level - 15) * 5;
        } else {
            return 7 + level * 2;
        }
    }

    public void setExperience(int exp) {
        setExperience(exp, this.getExperienceLevel());
    }

    //todo something on performance, lots of exp orbs then lots of packets, could crash client

    public void setExperience(int exp, int level) {
        this.exp = exp;
        this.expLevel = level;

        this.sendExperienceLevel(level);
        this.sendExperience(exp);
    }

    public void sendExperience() {
        sendExperience(this.getExperience());
    }

    public void sendExperience(int exp) {
        if (this.spawned) {
            float percent = ((float) exp) / calculateRequireExperience(this.getExperienceLevel());
            percent = Math.max(0f, Math.min(1f, percent));
            this.setAttribute(Attribute.getAttribute(Attribute.EXPERIENCE).setValue(percent));
        }
    }

    public void sendExperienceLevel() {
        sendExperienceLevel(this.getExperienceLevel());
    }

    public void sendExperienceLevel(int level) {
        if (this.spawned) {
            this.setAttribute(Attribute.getAttribute(Attribute.EXPERIENCE_LEVEL).setValue(level));
        }
    }

    public void setAttribute(Attribute attr) {
        UpdateAttributesPacket packet = new UpdateAttributesPacket();
        packet.getAttributes().add(NetworkUtils.attributeToNetwork(attr));
        packet.setRuntimeEntityId(this.getRuntimeId());
        this.sendPacket(packet);
    }

    @Override
    public void setMovementSpeed(float speed) {
        setMovementSpeed(speed, true);
    }

    public void setMovementSpeed(float speed, boolean send) {
        super.setMovementSpeed(speed);
        if (this.spawned && send) {
            Attribute attribute = Attribute.getAttribute(Attribute.MOVEMENT_SPEED).setValue(speed);
            this.setAttribute(attribute);
        }
    }

    public Entity getKiller() {
        return killer;
    }

    @Override
    public boolean attack(EntityDamageEvent source) {
        if (!this.isAlive()) {
            return false;
        }

        if (this.isSpectator() || (this.isCreative() && source.getCause() != EntityDamageEvent.DamageCause.SUICIDE)) {
            //source.setCancelled();
            return false;
        } else if (this.getAdventureSettings().get(AdventureSetting.ALLOW_FLIGHT) && source.getCause() == EntityDamageEvent.DamageCause.FALL) {
            //source.setCancelled();
            return false;
        } else if (source.getCause() == EntityDamageEvent.DamageCause.FALL) {
        }
        if (this.getLevel().getBlockState(this.getPosition().add(0, -1, 0).toInt()).getType() == BlockTypes.SLIME) {
            if (!this.isSneaking()) {
                //source.setCancelled();
                this.resetFallDistance();
                return false;
            }
        }

        if (super.attack(source)) { //!source.isCancelled()
            if (this.getLastDamageCause() == source && this.spawned) {
                if (source instanceof EntityDamageByEntityEvent) {
                    Entity damager = ((EntityDamageByEntityEvent) source).getDamager();
                    if (damager instanceof CloudPlayer) {
                        ((CloudPlayer) damager).getFoodData().updateFoodExpLevel(0.3);
                    }
                }
                EntityEventPacket packet = new EntityEventPacket();
                packet.setRuntimeEntityId(this.getRuntimeId());
                packet.setType(EntityEventType.HURT);
                this.sendPacket(packet);
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Drops an item on the ground in front of the player. Returns if the item drop was successful.
     *
     * @param item to drop
     * @return bool if the item was dropped or if the item was null
     */
    public boolean dropItem(ItemStack item) {
        if (!this.spawned || !this.isAlive()) {
            return false;
        }

        if (item.isNull()) {
            log.debug(this.getName() + " attempted to drop a null item (" + item + ")");
            return true;
        }

        Vector3f motion = this.getDirectionVector().mul(0.4);

        this.getLevel().dropItem(this.getPosition().add(0, 1.3, 0), item, motion, 40);

        this.setUsingItem(false);
        return true;
    }

    public void sendPosition(Vector3f pos) {
        this.sendPosition(pos, this.getYaw());
    }

    public void sendPosition(Vector3f pos, double yaw) {
        this.sendPosition(pos, yaw, this.getPitch());
    }

    public void sendPosition(Vector3f pos, double yaw, double pitch) {
        this.sendPosition(pos, yaw, pitch, MovePlayerPacket.Mode.NORMAL);
    }

    public void sendPosition(Vector3f pos, double yaw, double pitch, MovePlayerPacket.Mode mode) {
        this.sendPosition(pos, yaw, pitch, mode, null);
    }

    public void sendPosition(Vector3f pos, double yaw, double pitch, MovePlayerPacket.Mode mode, Set<CloudPlayer> targets) {
        MovePlayerPacket packet = new MovePlayerPacket();
        packet.setRuntimeEntityId(this.getRuntimeId());
        packet.setPosition(pos.add(0, getEyeHeight(), 0));
        packet.setRotation(Vector3f.from(pitch, yaw, yaw));
        packet.setMode(mode);
        if (mode == MovePlayerPacket.Mode.TELEPORT) {
            packet.setTeleportationCause(MovePlayerPacket.TeleportationCause.BEHAVIOR);
        }

        if (targets != null) {
            CloudServer.broadcastPacket(targets, packet);
        } else {
            this.sendPacket(packet);
        }
    }

    @Override
    public void loadAdditionalData(NbtMap tag) {
        this.playerData.loadData(tag);

        String level = this.playerData.getLevel();
        this.level = this.server.getLevel(level);
        if (this.level == null) {
            this.level = this.server.getDefaultLevel();
        }

        super.loadAdditionalData(tag);

        tag.listenForInt("GameType", (id) -> this.playerData.setGamemode(GameMode.from(id)));

        int exp = tag.getInt("EXP");
        int expLevel = tag.getInt("expLevel");
        this.setExperience(exp, expLevel);

        tag.listenForInt("foodLevel", this.foodData::setLevel);
        tag.listenForFloat("FoodSaturationLevel", this.foodData::setFoodSaturationLevel);
        tag.listenForList("EnderChestInventory", NbtType.COMPOUND, items -> {
            for (NbtMap itemTag : items) {
                this.getEnderChestInventory().setItem(itemTag.getByte("Slot"), ItemUtils.deserializeItem(itemTag));
            }
        });
    }

    @Override
    public void saveAdditionalData(NbtMapBuilder tag) {
        super.saveAdditionalData(tag);

        this.playerData.setLevel(this.level.getId());
        if (this.spawnLocation != null && this.spawnLocation.getLevel() != null) {
            this.playerData.setSpawnLevel(this.spawnLocation.getLevel().getId());
            this.playerData.setSpawnLocation(this.spawnLocation.getPosition().toInt());
        }

        this.playerData.saveData(tag);

        tag.putInt("GameType", this.getGamemode().getVanillaId());

        tag.putInt("EXP", this.getExperience());
        tag.putInt("expLevel", this.getExperienceLevel());

        tag.putInt("foodLevel", this.getFoodData().getLevel());
        tag.putFloat("foodSaturationLevel", this.getFoodData().getFoodSaturationLevel());
        this.invManager.getEnderChest().saveInventory(tag);

    }

    public void save(boolean async) {
        if (this.closed) {
            throw new IllegalStateException("Tried to save closed player");
        }

        if (!this.loggedIn || this.username.isEmpty()) {
            return; // No point in saving player data from here.
        }

        NbtMapBuilder tag = NbtMap.builder();
        this.saveAdditionalData(tag);

        this.server.saveOfflinePlayerData(this.identity, tag.build(), async);
    }

    @Override
    public void kill() {
        if (!this.spawned) {
            return;
        }

        boolean showMessages = this.getLevel().getGameRules().get(GameRules.SHOW_DEATH_MESSAGES);
        String message = "death.attack.generic";

        List<String> params = new ArrayList<>();
        params.add(this.getDisplayName());
        if (showMessages) {

            EntityDamageEvent cause = this.getLastDamageCause();

            switch (cause == null ? EntityDamageEvent.DamageCause.CUSTOM : cause.getCause()) {
                case ENTITY_ATTACK:
                    if (cause instanceof EntityDamageByEntityEvent) {
                        Entity e = ((EntityDamageByEntityEvent) cause).getDamager();
                        killer = e;
                        if (e instanceof CloudPlayer) {
                            message = "death.attack.player";
                            params.add(((CloudPlayer) e).getDisplayName());
                            break;
                        } else if (e instanceof EntityLiving) {
                            message = "death.attack.mob";
                            params.add(!Objects.equals(e.getNameTag(), "") ? e.getNameTag() : e.getName());
                            break;
                        } else {
                            params.add("Unknown");
                        }
                    }
                    break;
                case PROJECTILE:
                    if (cause instanceof EntityDamageByEntityEvent) {
                        Entity e = ((EntityDamageByEntityEvent) cause).getDamager();
                        killer = e;
                        if (e instanceof CloudPlayer) {
                            message = "death.attack.arrow";
                            params.add(((CloudPlayer) e).getDisplayName());
                        } else if (e instanceof EntityLiving) {
                            message = "death.attack.arrow";
                            params.add(!Objects.equals(e.getNameTag(), "") ? e.getNameTag() : e.getName());
                            break;
                        } else {
                            params.add("Unknown");
                        }
                    }
                    break;
                case SUICIDE:
                    message = "death.attack.generic";
                    break;
                case VOID:
                    message = "death.attack.outOfWorld";
                    break;
                case FALL:
                    if (cause != null) {
                        if (cause.getFinalDamage() > 2) {
                            message = "death.fell.accident.generic";
                            break;
                        }
                    }
                    message = "death.attack.fall";
                    break;

                case SUFFOCATION:
                    message = "death.attack.inWall";
                    break;

                case LAVA:
                    BlockState state = this.getLevel().getBlockState(this.getPosition().add(0, -1, 0).toInt());
                    if (state.getType() == BlockTypes.MAGMA) { //TODO: MAGMA should have its own DamageCause
                        message = "death.attack.magma";
                        break;
                    }
                    message = "death.attack.lava";
                    break;

                case FIRE:
                    message = "death.attack.onFire";
                    break;

                case FIRE_TICK:
                    message = "death.attack.inFire";
                    break;

                case DROWNING:
                    message = "death.attack.drown";
                    break;

                case CONTACT:
                    if (cause instanceof EntityDamageByBlockEvent) {
                        if (((EntityDamageByBlockEvent) cause).getDamager().getState().getType() == BlockTypes.CACTUS) {
                            message = "death.attack.cactus";
                        }
                    }
                    break;

                case BLOCK_EXPLOSION:
                case ENTITY_EXPLOSION:
                    if (cause instanceof EntityDamageByEntityEvent) {
                        Entity e = ((EntityDamageByEntityEvent) cause).getDamager();
                        killer = e;
                        if (e instanceof CloudPlayer) {
                            message = "death.attack.explosion.player";
                            params.add(((CloudPlayer) e).getDisplayName());
                        } else if (e instanceof EntityLiving) {
                            message = "death.attack.explosion.player";
                            params.add(!Objects.equals(e.getNameTag(), "") ? e.getNameTag() : e.getName());
                            break;
                        }
                    } else {
                        message = "death.attack.explosion";
                    }
                    break;

                case MAGIC:
                    message = "death.attack.magic";
                    break;

                case HUNGER:
                    message = "death.attack.starve";
                    break;

                case CUSTOM:
                    break;

                default:
                    break;

            }
        } else {
            message = "";
            params.clear();
        }

        if (this.fishing != null) {
            this.stopFishing(false);
        }

        this.health = 0;
        this.scheduleUpdate();

        PlayerDeathEvent ev = new PlayerDeathEvent(this, this.getDrops(), new TranslationContainer(message, params.toArray()), this.getExperienceLevel());

        ev.setKeepExperience(this.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY));
        ev.setKeepInventory(ev.getKeepExperience());
        this.server.getEventManager().fire(ev);

        if (!ev.getKeepInventory() && this.getLevel().getGameRules().get(GameRules.DO_ENTITY_DROPS)) {
            for (ItemStack item : ev.getDrops()) {
                this.getLevel().dropItem(this.getPosition(), item, null, true, 40);
            }

            this.getInventory().clearAll();
        }

        if (!ev.getKeepExperience() && this.getLevel().getGameRules().get(GameRules.DO_ENTITY_DROPS)) {
            if (this.isSurvival() || this.isAdventure()) {
                int exp = ev.getExperience() * 7;
                if (exp > 100) exp = 100;
                this.getLevel().dropExpOrb(this.getPosition(), exp);
            }
            this.setExperience(0, 0);
        }

        if (showMessages && !ev.getDeathMessage().toString().isEmpty()) {
            this.server.broadcast(ev.getDeathMessage(), CloudServer.BROADCAST_CHANNEL_USERS);
        }


        RespawnPacket packet = new RespawnPacket();
        Location location = this.getSpawn();
        packet.setPosition(location.getPosition());
        packet.setState(RespawnPacket.State.SERVER_SEARCHING);

        //this is a dirty hack to prevent dying in a different level than the respawn point from breaking everything
        if (this.getLevel() != location.getLevel()) {
            this.teleport(location, null);
        }

        this.extinguish();

        this.sendPacket(packet);
    }

    protected void sendPlayStatus(PlayStatusPacket.Status status) {
        sendPlayStatus(status, false);
    }

    protected void sendPlayStatus(PlayStatusPacket.Status status, boolean immediate) {
        PlayStatusPacket packet = new PlayStatusPacket();
        packet.setStatus(status);

        if (immediate) {
            this.sendPacketImmediately(packet);
        } else {
            this.sendPacket(packet);
        }
    }

    @Override
    protected void checkChunks() {
        Vector3f pos = this.getPosition();
        if (this.chunk == null || (this.chunk.getX() != pos.getFloorX() >> 4 || this.chunk.getZ() != pos.getFloorZ() >> 4)) {
            if (this.chunk != null) {
                this.chunk.removeEntity(this);
            }
            this.chunk = this.getLevel().getChunk(pos);

            if (!this.justCreated) {

                Set<CloudPlayer> loaders = this.getChunk().getPlayerLoaders();
                for (CloudPlayer player : this.hasSpawned) {
                    if (!loaders.contains(player)) {
                        this.despawnFrom(player);
                    } else {
                        loaders.remove(player);
                    }
                }

                for (CloudPlayer player : loaders) {
                    this.spawnTo(player);
                }
            }

            if (this.chunk == null) {
                return;
            }

            this.chunk.addEntity(this);
        }
    }

//    protected void forceSendEmptyChunks() {
//        int chunkPositionX = this.getChunkX();
//        int chunkPositionZ = this.getChunkZ();
//        for (int x = -chunkRadius; x < chunkRadius; x++) {
//            for (int z = -chunkRadius; z < chunkRadius; z++) {
//                LevelChunkPacket chunk = new LevelChunkPacket();
//                chunk.chunkX = chunkPositionX + x;
//                chunk.chunkZ = chunkPositionZ + z;
//                chunk.data = Unpooled.EMPTY_BUFFER;
//                this.dataPacket(chunk);
//            }
//        }
//    }

    public void teleportImmediate(Location location) {
        this.teleportImmediate(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    public void teleportImmediate(Location location, PlayerTeleportEvent.TeleportCause cause) {
        Location from = this.getLocation();
        if (super.teleport(location, cause)) {

            this.removeAllWindows(false);

            if (from.getLevel() != location.getLevel()) { //Different level, update compass position
                SetSpawnPositionPacket packet = new SetSpawnPositionPacket();
                packet.setSpawnType(SetSpawnPositionPacket.Type.WORLD_SPAWN);
                Vector3f spawn = location.getLevel().getSpawnLocation();
                packet.setBlockPosition(spawn.toInt());
                sendPacket(packet);
            }

            this.forceMovement = this.getPosition();
            log.debug("teleportImmediate REVERTING");
            this.sendPosition(this.getPosition(), this.getY(), this.getPitch(), MovePlayerPacket.Mode.RESPAWN);

            this.resetFallDistance();
            this.newPosition = null;

            //Weather
            this.getLevel().sendWeather(this);
            //Update time
            this.getLevel().sendTime(this);
        }
    }

    /**
     * Shows a new FormWindow to the player
     * You can find out FormWindow result by listening to PlayerFormRespondedEvent
     *
     * @param window to show
     * @return form id
     */
    public int showFormWindow(Form<?> window) {
        return showFormWindow(window, this.formWindowCount.getAndIncrement());
    }

    /**
     * Shows a new FormWindow to the player
     * You can find out FormWindow result by listening to PlayerFormRespondedEvent
     *
     * @param window to show
     * @param id     form id
     * @return form id
     */
    public int showFormWindow(Form<?> window, int id) {
        ModalFormRequestPacket packet = new ModalFormRequestPacket();
        packet.setFormId(id);
        try {
            packet.setFormData(new JsonMapper().writeValueAsString(window));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        this.formWindows.put(id, window);

        this.sendPacket(packet);
        return id;
    }

    public Form<?> removeFormWindow(int id) {
        return this.formWindows.remove(id);
    }

    public CustomForm getServerSettings() {
        return serverSettings;
    }

    public int getServerSettingsId() {
        return serverSettingsId;
    }

    /**
     * Shows a new setting page in game settings
     * You can find out settings result by listening to PlayerFormRespondedEvent
     *
     * @param window to show on settings page
     * @return form id
     */
    public int setServerSettings(CustomForm window) {
        int id = this.formWindowCount.getAndIncrement();

        this.serverSettings = window;
        this.serverSettingsId = id;
        return id;
    }

    /**
     * Creates and sends a BossBar to the player
     *
     * @param text   The BossBar message
     * @param length The BossBar percentage
     * @return bossBarId  The BossBar ID, you should store it if you want to remove or update the BossBar later
     */
    @Deprecated
    public long createBossBar(String text, int length) {
        DummyBossBar bossBar = new DummyBossBar.Builder(this).text(text).length(length).build();
        return this.createBossBar(bossBar);
    }

    /**
     * Creates and sends a BossBar to the player
     *
     * @param dummyBossBar DummyBossBar Object (Instantiate it by the Class Builder)
     * @return bossBarId  The BossBar ID, you should store it if you want to remove or update the BossBar later
     * @see DummyBossBar.Builder
     */
    public long createBossBar(DummyBossBar dummyBossBar) {
        this.dummyBossBars.put(dummyBossBar.getBossBarId(), dummyBossBar);
        dummyBossBar.create();
        return dummyBossBar.getBossBarId();
    }

    /**
     * Get a DummyBossBar object
     *
     * @param bossBarId The BossBar ID
     * @return DummyBossBar object
     * @see DummyBossBar#setText(String) Set BossBar text
     * @see DummyBossBar#setLength(float) Set BossBar length
     * @see DummyBossBar#setColor(DummyBossBar.BossBarColor) Set BossBar color
     */
    public DummyBossBar getDummyBossBar(long bossBarId) {
        return this.dummyBossBars.getOrDefault(bossBarId, null);
    }

    /**
     * Get all DummyBossBar objects
     *
     * @return DummyBossBars Map
     */
    public Map<Long, DummyBossBar> getDummyBossBars() {
        return dummyBossBars;
    }

    /**
     * Updates a BossBar
     *
     * @param text      The new BossBar message
     * @param length    The new BossBar length
     * @param bossBarId The BossBar ID
     */
    @Deprecated
    public void updateBossBar(String text, int length, long bossBarId) {
        if (this.dummyBossBars.containsKey(bossBarId)) {
            DummyBossBar bossBar = this.dummyBossBars.get(bossBarId);
            bossBar.setText(text);
            bossBar.setLength(length);
        }
    }

    /**
     * Removes a BossBar
     *
     * @param bossBarId The BossBar ID
     */
    public void removeBossBar(long bossBarId) {
        if (this.dummyBossBars.containsKey(bossBarId)) {
            this.dummyBossBars.get(bossBarId).destroy();
            this.dummyBossBars.remove(bossBarId);
        }
    }

    @Override
    public byte getWindowId(Inventory inventory) {
        if (this.windows.containsKey(inventory)) {
            return this.windows.get(inventory);
        }

        return -1;
    }

    public Inventory getWindowById(int id) {
        return this.windowIndex.get((byte) id);
    }

    public byte addWindow(Inventory inventory, Byte forceId, boolean isPermanent) {
        if (this.windows.containsKey(inventory)) {
            return this.windows.get(inventory);
        }
        byte cnt;
        if (forceId == null) {
            this.windowCnt = cnt = (byte) Math.max(4, ++this.windowCnt % 99);
        } else {
            cnt = forceId;
        }

        this.windows.forcePut(inventory, cnt);

        if (isPermanent) {
            this.permanentWindows.add(cnt);
        }

        if (inventory.open(this)) {
            return cnt;
        } else {
            this.removeWindow(inventory);

            return -1;
        }
    }

    public Optional<Inventory> getTopWindow() {
        for (Entry<Inventory, Byte> entry : this.windows.entrySet()) {
            if (!this.permanentWindows.contains((byte) entry.getValue())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public void removeWindow(Inventory inventory) {
        inventory.close(this);
        if (!this.permanentWindows.contains(this.getWindowId(inventory)))
            this.windows.remove(inventory);
    }

    public Inventory removeWindowById(byte id) {
        Inventory inventory = this.windowIndex.remove(id);
        if (inventory != null)
            inventory.close(this);
        return inventory;
    }

    public void sendAllInventories() {
        for (Inventory inv : this.windows.keySet()) {
            inv.sendContents(this);

            if (inv instanceof CloudPlayerInventory) {
                ((CloudPlayerInventory) inv).sendArmorContents(this);
            }
        }
    }

    protected void addDefaultWindows() {
        this.addWindow(this.getInventory(), (byte) ContainerId.INVENTORY, true);
        this.addWindow(this.getCraftingInventory(), (byte) ContainerId.NONE);
        this.addWindow(this.getCursorInventory(), (byte) ContainerId.UI, true); // Is this needed?

        //TODO: more windows
    }

    @Override
    public CloudPlayerInventory getInventory() {
        return invManager.getPlayerInventory();
    }

    @Override
    public CloudEnderChestInventory getEnderChestInventory() {
        return invManager.getEnderChest();
    }

    public PlayerCursorInventory getCursorInventory() {
        return this.invManager.getCursor();
    }

    public PlayerInventoryManager getInventoryManager() {
        return invManager;
    }


    @Nullable
    public CraftItemStackTransaction getCraftingTransaction() {
        return invManager.getCraftingTransaction();
    }

    public void setCraftingTransaction(@Nullable CraftItemStackTransaction craftingTransaction) {
        this.invManager.setTransaction(craftingTransaction);
    }

    public void removeAllWindows() {
        removeAllWindows(false);
    }

    public void removeAllWindows(boolean permanent) {
        for (Entry<Byte, Inventory> entry : new ArrayList<>(this.windowIndex.entrySet())) {
            if (!permanent && this.permanentWindows.contains((byte) entry.getKey())) {
                continue;
            }
            this.removeWindow(entry.getValue());
        }
    }

/*    @Override
    public void setMetadata(String metadataKey, MetadataValue newMetadataValue) {
        this.server.getPlayerMetadata().setMetadata(this, metadataKey, newMetadataValue);
    }

    @Override
    public List<MetadataValue> getMetadata(String metadataKey) {
        return this.server.getPlayerMetadata().getMetadata(this, metadataKey);
    }

    @Override
    public boolean hasMetadata(String metadataKey) {
        return this.server.getPlayerMetadata().hasMetadata(this, metadataKey);
    }

    @Override
    public void removeMetadata(String metadataKey, PluginContainer owningPlugin) {
        this.server.getPlayerMetadata().removeMetadata(this, metadataKey, owningPlugin);
    }*/

    protected boolean checkTeleportPosition() {
        if (this.teleportPosition != null) {
            int chunkX = this.teleportPosition.getFloorX() >> 4;
            int chunkZ = this.teleportPosition.getFloorZ() >> 4;

            for (int X = -1; X <= 1; ++X) {
                for (int Z = -1; Z <= 1; ++Z) {
                    long index = CloudChunk.key(chunkX + X, chunkZ + Z);
                    if (!this.getChunkManager().isChunkInView(index)) {
                        return false;
                    }
                }
            }

            this.spawnToAll();
            this.forceMovement = this.teleportPosition;
            this.teleportPosition = null;
            return true;
        }

        return false;
    }

    @Override
    public boolean teleport(Location location, PlayerTeleportEvent.TeleportCause cause) {
        if (!this.isOnline()) {
            return false;
        }

        Location from = this.getLocation();
        Location to = location;

        if (cause != null) {
            PlayerTeleportEvent event = new PlayerTeleportEvent(this, from, to, cause);
            this.server.getEventManager().fire(event);
            if (event.isCancelled()) return false;
            to = event.getTo();
        }

        //TODO Remove it! A hack to solve the client-side teleporting bug! (inside into the block)
        if (super.teleport(to.getY() == to.getFloorY() ? to.add(0, 0.00001, 0) : to, null)) { // null to prevent fire of duplicate EntityTeleportEvent
            this.removeAllWindows();

            this.teleportPosition = this.getPosition();
            this.getChunkManager().queueNewChunks(this.teleportPosition);
            this.forceMovement = this.teleportPosition;
            this.sendPosition(this.getPosition(), this.getYaw(), this.getPitch(), MovePlayerPacket.Mode.TELEPORT);

            this.checkTeleportPosition();

            this.resetFallDistance();
            this.newPosition = null;

            //DummyBossBar
            this.getDummyBossBars().values().forEach(DummyBossBar::reshow);
            //Weather
            this.getLevel().sendWeather(this);
            //Update time
            this.getLevel().sendTime(this);
            return true;
        }

        return false;
    }

    public boolean isChunkInView(int x, int z) {
        return this.getChunkManager().isChunkInView(x, z);
    }

    @Override
    public void onChunkChanged(Chunk chunk) {
        this.getChunkManager().resendChunk(chunk.getX(), chunk.getZ());
    }

    @Override
    public int getLoaderId() {
        return this.loaderId;
    }

    @Override
    public boolean isLoaderActive() {
        return this.isConnected();
    }

    private boolean foodEnabled = true;

    public boolean isFoodEnabled() {
        return !(this.isCreative() || this.isSpectator()) && this.foodEnabled;
    }

    public void setFoodEnabled(boolean foodEnabled) {
        this.foodEnabled = foodEnabled;
    }

    public PlayerFood getFoodData() {
        return this.foodData;
    }

    //todo a lot on dimension

    private void setDimension(int dimension) {
        ChangeDimensionPacket packet = new ChangeDimensionPacket();
        packet.setDimension(dimension);
        packet.setPosition(this.getPosition());
        this.sendPacketImmediately(packet);
    }

    public synchronized void setLocale(Locale locale) {
        this.locale.set(locale);
    }

    public synchronized Locale getLocale() {
        return this.locale.get();
    }

    @Override
    public void setSprinting(boolean value) {
        if (isSprinting() != value) {
            super.setSprinting(value);
            this.setMovementSpeed(value ? getMovementSpeed() * 1.3f : getMovementSpeed() / 1.3f);
        }
    }

    public void transfer(InetSocketAddress address) {
        String hostName = address.getAddress().getHostAddress();
        int port = address.getPort();
        TransferPacket pk = new TransferPacket();
        pk.setAddress(hostName);
        pk.setPort(port);
        this.sendPacket(pk);
    }

    public LoginChainData getLoginChainData() {
        return this.loginChainData;
    }

    public void setLoginChainData(LoginChainData loginChainData) {
        this.loginChainData = loginChainData;
    }

    @Override
    public void onChunkUnloaded(Chunk chunk) {
        //this.sentChunks.remove(Chunk.key(chunk.getX(), chunk.getZ()));
    }

    @Override
    public void onChunkLoaded(Chunk chunk) {

    }

    @Override
    public int hashCode() {
        if ((this.hash == 0) || (this.hash == 485)) {
            this.hash = (485 + (getServerId() != null ? getServerId().hashCode() : 0));
        }

        return this.hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CloudPlayer)) {
            return false;
        }
        CloudPlayer other = (CloudPlayer) obj;
        return Objects.equals(this.getServerId(), other.getServerId()) && this.getUniqueId() == other.getUniqueId();
    }

    public boolean isBreakingBlock() {
        return this.breakingBlock != null;
    }

    /**
     * Show a window of a XBOX account's profile
     *
     * @param xuid XUID
     */
    public void showXboxProfile(String xuid) {
        ShowProfilePacket packet = new ShowProfilePacket();
        packet.setXuid(xuid);
        this.sendPacket(packet);
    }

    /**
     * Sends a sign change. Sends "fake" sign contents
     * by sending a sign change packet. This does not actually
     * modify the sign lines and is only temporary.
     *
     * @param position the position of the sign
     * @param lines    the lines to send
     */
    public void sendSignChange(Vector3i position, String[] lines) {
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (!(blockEntity instanceof Sign)) {
            return;
        }
        NbtMap tag = ((SignBlockEntity) blockEntity).getChunkTag().toBuilder().putString("Text", String.join("\n", lines)).build();
        BlockEntityDataPacket blockEntityDataPacket = new BlockEntityDataPacket();
        blockEntityDataPacket.setBlockPosition(position);
        blockEntityDataPacket.setData(tag);
        this.sendPacket(blockEntityDataPacket);
    }

    /**
     * Start fishing
     * @param fishingRod fishing rod item
     */
    public void startFishing(ItemStack fishingRod) {
        Location location = Location.from(this.getPosition().add(0, this.getEyeHeight(), 0), this.getYaw(),
                this.getPitch(), this.getLevel());
        double f = 1.1;
        FishingHook fishingHook = EntityRegistry.get().newEntity(EntityTypes.FISHING_HOOK, location);
        fishingHook.setPosition(location.getPosition());
        fishingHook.setOwner(this);
        fishingHook.setMotion(Vector3f.from(-Math.sin(Math.toRadians(this.getYaw())) * Math.cos(Math.toRadians(this.getPitch())) * f * f,
                -Math.sin(Math.toRadians(this.getPitch())) * f * f, Math.cos(Math.toRadians(this.getYaw())) * Math.cos(Math.toRadians(this.getPitch())) * f * f));
        ProjectileLaunchEvent ev = new ProjectileLaunchEvent(fishingHook);
        this.getServer().getEventManager().fire(ev);
        if (ev.isCancelled()) {
            fishingHook.close();
        } else {
            fishingHook.spawnToAll();
            this.fishing = fishingHook;
            fishingHook.setRod(fishingRod);
        }
    }

    /**
     * Stop fishing
     * @param click clicked or forced
     */
    public void stopFishing(boolean click) {
        if (this.fishing != null && click) {
            fishing.reelLine();
        } else if (this.fishing != null) {
            this.fishing.close();
        }

        this.fishing = null;
    }

    @Override
    public boolean switchLevel(CloudLevel level) {
        CloudLevel oldLevel = this.getLevel();
        if (super.switchLevel(level)) {
            SetSpawnPositionPacket spawnPosition = new SetSpawnPositionPacket();
            spawnPosition.setSpawnType(SetSpawnPositionPacket.Type.WORLD_SPAWN);
            Vector3f spawn = level.getSpawnLocation();
            spawnPosition.setBlockPosition(spawn.toInt());
            this.sendPacket(spawnPosition);

            this.getChunkManager().prepareRegion(this.getPosition());

            SetTimePacket setTime = new SetTimePacket();
            setTime.setTime(level.getTime());
            this.sendPacket(setTime);

            GameRulesChangedPacket gameRulesChanged = new GameRulesChangedPacket();
            NetworkUtils.gameRulesToNetwork(level.getGameRules(), gameRulesChanged.getGameRules());
            this.sendPacket(gameRulesChanged);
            return true;
        }

        return false;
    }

    public boolean pickupEntity(Entity entity, boolean near) {
        if (!this.spawned || !this.isAlive() || !this.isOnline() || this.getGamemode() == GameMode.SPECTATOR || entity.isClosed()) {
            return false;
        }

        if (near) {
            if (entity instanceof Arrow && entity.getMotion().lengthSquared() == 0) {
                ItemStack item = CloudItemRegistry.get().getItem(ItemTypes.ARROW);
                if (this.isSurvival() && !this.getInventory().canAddItem(item)) {
                    return false;
                }

                InventoryPickupArrowEvent ev = new InventoryPickupArrowEvent(this.getInventory(), (EntityArrow) entity);

                int pickupMode = ((EntityArrow) entity).getPickupMode();
                if (pickupMode == EntityArrow.PICKUP_NONE || pickupMode == EntityArrow.PICKUP_CREATIVE && !this.isCreative()) {
                    ev.setCancelled();
                }

                this.server.getEventManager().fire(ev);
                if (ev.isCancelled()) {
                    return false;
                }

                TakeItemEntityPacket packet = new TakeItemEntityPacket();
                packet.setRuntimeEntityId(this.getRuntimeId());
                packet.setItemRuntimeEntityId(entity.getRuntimeId());
                CloudServer.broadcastPacket(((BaseEntity) entity).getViewers(), packet);
                this.sendPacket(packet);

                if (!this.isCreative()) {
                    this.getInventory().addItem(item);
                }
                entity.close();
                return true;
            } else if (entity instanceof ThrownTrident && entity.getMotion().lengthSquared() == 0) {
                ItemStack item = ((ThrownTrident) entity).getTrident();
                if (this.isSurvival() && !this.getInventory().canAddItem(item)) {
                    return false;
                }

                TakeItemEntityPacket packet = new TakeItemEntityPacket();
                packet.setRuntimeEntityId(this.getRuntimeId());
                packet.setItemRuntimeEntityId(entity.getRuntimeId());
                CloudServer.broadcastPacket(((BaseEntity) entity).getViewers(), packet);
                this.sendPacket(packet);

                if (!this.isCreative()) {
                    this.getInventory().addItem(item);
                }
                entity.close();
                return true;
            } else if (entity instanceof DroppedItem) {
                if (((DroppedItem) entity).getPickupDelay() <= 0) {
                    ItemStack item = ((DroppedItem) entity).getItem();

                    if (item != null) {
                        if (this.isSurvival() && !this.getInventory().canAddItem(item)) {
                            return false;
                        }

                        InventoryPickupItemEvent ev;
                        this.server.getEventManager().fire(ev = new InventoryPickupItemEvent(this.getInventory(), (DroppedItem) entity));
                        if (ev.isCancelled()) {
                            return false;
                        }

                        if (item.getType() == BlockTypes.LOG) {
                            this.awardAchievement("mineWood");
                        } else if (item.getType() == ItemTypes.DIAMOND) {
                            this.awardAchievement("diamond");
                        }

                        TakeItemEntityPacket packet = new TakeItemEntityPacket();
                        packet.setRuntimeEntityId(this.getRuntimeId());
                        packet.setItemRuntimeEntityId(entity.getRuntimeId());
                        CloudServer.broadcastPacket(((BaseEntity) entity).getViewers(), packet);
                        this.sendPacket(packet);

                        entity.close();
                        this.getInventory().addItem(item);
                        return true;
                    }
                }
            }
        }

        int tick = this.getServer().getTick();
        if (pickedXPOrb < tick && entity instanceof ExperienceOrb && this.boundingBox.isVectorInside(entity.getPosition())) {
            ExperienceOrb experienceOrb = (ExperienceOrb) entity;
            if (experienceOrb.getPickupDelay() <= 0) {
                int exp = experienceOrb.getExperience();
                entity.kill();
                this.getLevel().addSound(this.getPosition(), Sound.RANDOM_ORB);
                pickedXPOrb = tick;

                //Mending
                ArrayList<Integer> itemsWithMending = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    if (getInventory().getArmorItem(i).getEnchantment(EnchantmentTypes.MENDING) != null) {
                        itemsWithMending.add(getInventory().getSize() + i);
                    }
                }
                if (getInventory().getItemInHand().getEnchantment(EnchantmentTypes.MENDING) != null) {
                    itemsWithMending.add(getInventory().getHeldItemIndex());
                }
                if (itemsWithMending.size() > 0) {
                    Random rand = new Random();
                    Integer itemToRepair = itemsWithMending.get(rand.nextInt(itemsWithMending.size()));
                    ItemStack toRepair = getInventory().getItem(itemToRepair);
                    var behavior = toRepair.getBehavior();
                    if (behavior.isTool(toRepair) || behavior.isArmor()) {
                        var damage = toRepair.getMetadata(Damageable.class);

                        if (damage.getDurability() > 0) {
                            getInventory().setItem(itemToRepair, toRepair.withData(damage.repair(2)));
                            return true;
                        }
                    }
                }

                this.addExperience(exp);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean canTriggerPressurePlate() {
        return !this.isSpectator();
    }

    @Override
    public String toString() {
        return "Player(name=" + getName() + ")";
    }

    private class Handler implements BatchHandler {

        @Override
        public void handle(BedrockSession session, ByteBuf buffer, Collection<BedrockPacket> packets) {
            for (BedrockPacket packet : packets) {
                CloudPlayer.this.handleDataPacket(packet);
            }
        }
    }

    public enum CraftingType {
        SMALL,
        BIG,
        ANVIL,
        ENCHANT,
        BEACON
    }

}
