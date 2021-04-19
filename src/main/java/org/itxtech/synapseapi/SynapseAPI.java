package org.itxtech.synapseapi;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.server.BatchPacketsEvent;
import cn.nukkit.network.RakNetInterface;
import cn.nukkit.network.SourceInterface;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.ConfigSection;
import cn.nukkit.utils.VarInt;
import org.itxtech.synapseapi.messaging.Messenger;
import org.itxtech.synapseapi.messaging.StandardMessenger;
import org.itxtech.synapseapi.utils.DataPacketEidReplacer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/**
 * @author boybook
 */
public class SynapseAPI extends PluginBase implements Listener {

    public static boolean enable = true;
    private static SynapseAPI instance;
    private boolean autoConnect = true;
    private Map<String, SynapseEntry> synapseEntries = new HashMap<>();
    private Messenger messenger;

    public static SynapseAPI getInstance() {
        return instance;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        this.messenger = new StandardMessenger();
        loadEntries();

        this.getServer().getPluginManager().registerEvents(this, this);
    }

    public Map<String, SynapseEntry> getSynapseEntries() {
        return synapseEntries;
    }

    public void addSynapseAPI(SynapseEntry entry) {
        this.synapseEntries.put(entry.getHash(), entry);
    }

    public SynapseEntry getSynapseEntry(String hash) {
        return this.synapseEntries.get(hash);
    }

    public void shutdownAll() {
        for (SynapseEntry entry : new ArrayList<>(this.synapseEntries.values())) {
            entry.shutdown();
        }
    }

    @Override
    public void onDisable() {
        this.shutdownAll();
    }

    public DataPacket getPacket(byte[] buffer) {
        ByteArrayInputStream bais = new ByteArrayInputStream(buffer);
        int header;
        try {
            header = (int) VarInt.readUnsignedVarInt(bais);
        } catch (IOException e) {
            throw new RuntimeException("Unable to decode packet header", e);
        }

        // | Client ID | Sender ID | Packet ID |
        // |   2 bits  |   2 bits  |  10 bits  |
        int packetId = header & 0x3ff;

        DataPacket packet = this.getServer().getNetwork().getPacket(packetId == 0xfe ? 0xff : packetId);

        if (packet != null) {
            packet.setBuffer(buffer, buffer.length - bais.available());
        }

        return packet;
    }

    private void loadEntries() {
        this.saveDefaultConfig();
        enable = this.getConfig().getBoolean("enable", true);

        if (!enable) {
            this.getLogger().warning("The SynapseAPI is not be enabled!");
        } else {
            if (this.getConfig().getBoolean("disable-rak")) {
                for (SourceInterface sourceInterface : this.getServer().getNetwork().getInterfaces()) {
                    if (sourceInterface instanceof RakNetInterface) {
                        sourceInterface.shutdown();
                    }
                }
            }

            List entries = this.getConfig().getList("entries");

            for (Object entry : entries) {
                @SuppressWarnings("unchecked")
                ConfigSection section = new ConfigSection((LinkedHashMap) entry);
                String serverIp = section.getString("server-ip", "127.0.0.1");
                int port = section.getInt("server-port", 10305);
                boolean isMainServer = section.getBoolean("isMainServer");
                boolean isLobbyServer = section.getBoolean("isLobbyServer");
                boolean transfer = section.getBoolean("transferOnShutdown", true);
                String password = section.getString("password");
                String serverDescription = section.getString("description");
                this.autoConnect = section.getBoolean("autoConnect", true);
                if (this.autoConnect) {
                    this.addSynapseAPI(new SynapseEntry(this, serverIp, port, isMainServer, isLobbyServer, transfer, password, serverDescription));
                }
            }
        }
    }

    public Messenger getMessenger() {
        return messenger;
    }

    @EventHandler
    public void onBatchPackets(BatchPacketsEvent e) {
        e.setCancelled();
        Player[] players = e.getPlayers();

        DataPacket[] packets = e.getPackets();

        HashMap<SynapseEntry, Map<Player, DataPacket[]>> map = new HashMap<>();

        for (Player p : players) {
            if (!(p instanceof SynapsePlayer)) {
                // We don't need to replace ids in non-synapse player packets
                continue;
            }
            SynapsePlayer player = (SynapsePlayer) p;

            SynapseEntry entry = player.getSynapseEntry();
            Map<Player, DataPacket[]> playerPackets = map.get(entry);
            if (playerPackets == null) {
                playerPackets = new HashMap<>();
            }

            DataPacket[] replaced = Arrays.stream(packets)
                    .map(packet -> DataPacketEidReplacer.replace(packet, p.getId(), SynapsePlayer.REPLACE_ID))
                    .toArray(DataPacket[]::new);

            playerPackets.put(player, replaced);

            map.put(entry, playerPackets);
        }

        for (Map.Entry<SynapseEntry, Map<Player, DataPacket[]>> entry : map.entrySet()) {
            for (Map.Entry<Player, DataPacket[]> playerEntry : entry.getValue().entrySet()) {
                for (DataPacket pk : playerEntry.getValue()) {
                    playerEntry.getKey().dataPacket(pk);
                }
            }
//            entry.getKey().getSynapseInterface().getPutPacketThread().addMainToThreadBroadcast(entry.getValue());
        }
    }
}
