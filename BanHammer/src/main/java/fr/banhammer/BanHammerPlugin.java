package fr.banhammer;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.util.*;

public final class BanHammerPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey itemKey, targetKey, blockKey;
    private final Map<UUID, Hunt> hunts = new HashMap<>();
    private final Map<String, Deban> debans = new HashMap<>();
    private final Map<UUID, String> selectedDeban = new HashMap<>();
    private final Map<Location, String> debanBlocks = new HashMap<>();
    private static final long HUNT_MS = 60L * 60L * 1000L;
    private static final long DEBAN_MS = 30L * 60L * 1000L;

    @Override public void onEnable() {
        itemKey = new NamespacedKey(this, "hammer_type");
        targetKey = new NamespacedKey(this, "deban_target");
        blockKey = new NamespacedKey(this, "deban_block");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("banhammer")).setExecutor(this);
        Objects.requireNonNull(getCommand("debanhammer")).setExecutor(this);
        loadData(); registerRecipes();
        new BukkitRunnable() { @Override public void run() { tickTimers(); } }.runTaskTimer(this, 20L, 20L);
    }

    @Override public void onDisable() { saveData(); }

    private ItemStack hammer(String type) {
        ItemStack item = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(type.equals("ban") ? ChatColor.DARK_RED + "Ban Hammer" : ChatColor.AQUA + "Débannisseur");
        meta.setLore(type.equals("ban") ? List.of(ChatColor.GRAY + "Clic droit sur un joueur pour lancer une chasse", ChatColor.RED + "60 minutes pour le tuer") : List.of(ChatColor.GRAY + "Sélectionne une cible bannie via /debanhammer", ChatColor.AQUA + "Place le bloc : 30 min avant le déban"));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta); return item;
    }
    private String type(ItemStack item) { if (item == null || !item.hasItemMeta()) return null; return item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING); }

    private void registerRecipes() {
        ShapedRecipe ban = new ShapedRecipe(new NamespacedKey(this, "ban_hammer"), hammer("ban"));
        ban.shape("NNN", "PPP", "SSS"); ban.setIngredient('N', Material.NETHERITE_BLOCK); ban.setIngredient('P', Material.PIGLIN_HEAD); ban.setIngredient('S', Material.NETHER_STAR); getServer().addRecipe(ban);
        ShapedRecipe deban = new ShapedRecipe(new NamespacedKey(this, "deban_hammer"), hammer("deban"));
        deban.shape("NNN", "NNN", "NNN"); deban.setIngredient('N', Material.NETHERITE_BLOCK); getServer().addRecipe(deban);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Commande réservée aux joueurs."); return true; }
        if (!p.hasPermission("banhammer.admin")) { p.sendMessage(ChatColor.RED + "Permission refusée."); return true; }
        if (command.getName().equalsIgnoreCase("banhammer")) { p.getInventory().addItem(hammer("ban")); p.sendMessage(ChatColor.GREEN + "Ban Hammer reçu."); return true; }
        if (args.length == 0) { p.getInventory().addItem(hammer("deban")); p.sendMessage(ChatColor.GREEN + "Débannisseur reçu. Utilise /debanhammer <joueur> pour choisir une cible bannie."); return true; }
        String name = args[0];
        if (!isBanned(name)) { p.sendMessage(ChatColor.RED + name + " ne semble pas être banni (vérifie son pseudo exact)."); return true; }
        selectedDeban.put(p.getUniqueId(), name);
        ItemStack item = hammer("deban"); ItemMeta meta = item.getItemMeta(); meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, name); item.setItemMeta(meta);
        p.getInventory().addItem(item); p.sendMessage(ChatColor.AQUA + "Débannisseur lié à " + name + ". Place-le pour démarrer les 30 minutes."); return true;
    }

    @EventHandler(ignoreCancelled = true) public void onInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem(); String t = type(item); if (t == null) return;
        if (t.equals("ban") && e.getAction().name().contains("RIGHT_CLICK")) {
            if (e.getClickedBlock() != null) return;
            Player target = e.getPlayer().getWorld().getPlayers().stream().filter(x -> x != e.getPlayer() && x.isOnline() && x.getBoundingBox().overlaps(e.getPlayer().getBoundingBox().expand(2.5))).min(Comparator.comparingDouble(x -> x.getLocation().distanceSquared(e.getPlayer().getLocation()))).orElse(null);
            if (target == null) { e.getPlayer().sendMessage(ChatColor.RED + "Clic droit directement sur le joueur pour le sélectionner."); return; }
            e.setCancelled(true); startHunt(e.getPlayer(), target);
        } else if (t.equals("deban") && e.getAction().name().contains("RIGHT_CLICK") && e.getClickedBlock() != null) {
            ItemMeta meta = item.getItemMeta(); String target = meta.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
            if (target == null) target = selectedDeban.get(e.getPlayer().getUniqueId());
            if (target == null || !isBanned(target)) { e.getPlayer().sendMessage(ChatColor.RED + "Choisis d'abord une cible bannie avec /debanhammer <joueur>."); return; }
            e.setCancelled(true); Block place = e.getClickedBlock().getRelative(e.getBlockFace());
            if (!place.getType().isAir()) { e.getPlayer().sendMessage(ChatColor.RED + "Il faut un emplacement libre."); return; }
            place.setType(Material.LODESTONE);
            Location loc = place.getLocation(); debanBlocks.put(loc, target); debans.put(target.toLowerCase(Locale.ROOT), new Deban(target, System.currentTimeMillis() + DEBAN_MS, loc));
            item.setAmount(item.getAmount()-1); e.getPlayer().sendMessage(ChatColor.AQUA + "Bloc de déban placé pour " + target + ". Déban dans 30 minutes si le bloc survit."); saveData();
        }
    }

    @EventHandler(ignoreCancelled = true) public void onPlayerInteractEntity(PlayerInteractAtEntityEvent e) {
        if (!(e.getRightClicked() instanceof Player target)) return;
        if (!"ban".equals(type(e.getPlayer().getInventory().getItemInMainHand()))) return;
        e.setCancelled(true);
        startHunt(e.getPlayer(), target);
    }

    private void startHunt(Player hunter, Player target) {
        if (hunts.containsKey(target.getUniqueId())) { hunter.sendMessage(ChatColor.RED + "Ce joueur est déjà une cible."); return; }
        hunts.put(target.getUniqueId(), new Hunt(hunter.getUniqueId(), target.getName(), System.currentTimeMillis()+HUNT_MS));
        hunter.sendMessage(ChatColor.DARK_RED + "Chasse lancée contre " + target.getName() + " : 60 minutes !"); target.sendMessage(ChatColor.RED + "Tu es ciblé par " + hunter.getName() + ". Survis 60 minutes !");
        for (Player p : Bukkit.getOnlinePlayers()) if (p != hunter && p != target) p.sendMessage(ChatColor.GRAY + target.getName() + " est la cible d'une chasse."); saveData();
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity(); Hunt hunt = hunts.get(victim.getUniqueId()); if (hunt == null) return;
        Player killer = victim.getKiller();
        if (killer != null && killer.getUniqueId().equals(hunt.hunter)) {
            Bukkit.getBanList(BanList.Type.NAME).addBan(hunt.targetName, "Banni par le Ban Hammer", null, killer.getName());
            Bukkit.getScheduler().runTask(this, () -> { Player online = Bukkit.getPlayer(victim.getUniqueId()); if (online != null) online.kickPlayer(ChatColor.DARK_RED + "Tu as été banni par le Ban Hammer."); });
            Bukkit.broadcastMessage(ChatColor.DARK_RED + hunt.targetName + " a été banni par le Ban Hammer de " + killer.getName() + " !");
        }
        hunts.remove(victim.getUniqueId()); saveData();
    }

    @EventHandler(ignoreCancelled = true) public void onBlockBreak(BlockBreakEvent e) {
        Location loc = e.getBlock().getLocation(); String target = debanBlocks.remove(loc);
        if (target != null) { debans.remove(target.toLowerCase(Locale.ROOT)); e.getBlock().setType(Material.AIR); e.setDropItems(false); e.getPlayer().sendMessage(ChatColor.RED + "Bloc détruit : déban annulé pour " + target + "."); Bukkit.broadcastMessage(ChatColor.RED + e.getPlayer().getName() + " a détruit le bloc de déban de " + target + " !"); saveData(); }
    }

    private void tickTimers() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID,Hunt>> hi = hunts.entrySet().iterator();
        while (hi.hasNext()) { var en=hi.next(); Hunt h=en.getValue(); if (now >= h.end) { Player target=Bukkit.getPlayer(en.getKey()); if(target!=null) target.sendMessage(ChatColor.GREEN+"Tu as survécu à la chasse !"); Player hunter=Bukkit.getPlayer(h.hunter); if(hunter!=null) hunter.sendMessage(ChatColor.YELLOW+"La chasse contre "+h.targetName+" a expiré."); hi.remove(); saveData(); } }
        Iterator<Map.Entry<String,Deban>> di=debans.entrySet().iterator();
        while(di.hasNext()) { var en=di.next(); Deban d=en.getValue(); if(now>=d.end) { if(d.location!=null && d.location.getBlock().getType()==Material.LODESTONE) d.location.getBlock().setType(Material.AIR); debanBlocks.remove(d.location); Bukkit.getBanList(BanList.Type.NAME).pardon(d.name); Bukkit.broadcastMessage(ChatColor.AQUA+d.name+" a été débanni grâce au Débannisseur !"); di.remove(); saveData(); } }
    }

    private boolean isBanned(String name) { return Bukkit.getBanList(BanList.Type.NAME).isBanned(name); }
    private void saveData() {
        try { getDataFolder().mkdirs(); try(DataOutputStream out=new DataOutputStream(new FileOutputStream(new File(getDataFolder(),"data.bin")))) {
            out.writeInt(hunts.size()); for(var e:hunts.entrySet()){out.writeUTF(e.getKey().toString());out.writeUTF(e.getValue().hunter.toString());out.writeUTF(e.getValue().targetName);out.writeLong(e.getValue().end);}
            out.writeInt(debans.size()); for(var e:debans.entrySet()){Deban d=e.getValue();out.writeUTF(d.name);out.writeLong(d.end);out.writeBoolean(d.location!=null);if(d.location!=null){out.writeUTF(d.location.getWorld().getName());out.writeDouble(d.location.getX());out.writeDouble(d.location.getY());out.writeDouble(d.location.getZ());}}
        }} catch(Exception ex){getLogger().warning("Impossible de sauvegarder: "+ex.getMessage());}
    }
    private void loadData() {
        File f=new File(getDataFolder(),"data.bin"); if(!f.exists())return;
        try(DataInputStream in=new DataInputStream(new FileInputStream(f))){int n=in.readInt();for(int i=0;i<n;i++){UUID target=UUID.fromString(in.readUTF()),hunter=UUID.fromString(in.readUTF());String name=in.readUTF();long end=in.readLong();if(end>System.currentTimeMillis())hunts.put(target,new Hunt(hunter,name,end));}
            int m=in.readInt();for(int i=0;i<m;i++){String name=in.readUTF();long end=in.readLong();Location loc=null;if(in.readBoolean()){World w=Bukkit.getWorld(in.readUTF());double x=in.readDouble(),y=in.readDouble(),z=in.readDouble();if(w!=null)loc=new Location(w,x,y,z);}if(end>System.currentTimeMillis()){Deban d=new Deban(name,end,loc);debans.put(name.toLowerCase(Locale.ROOT),d);if(loc!=null)debanBlocks.put(loc,name);}}
        }catch(Exception ex){getLogger().warning("Sauvegarde illisible: "+ex.getMessage());}
    }
    private record Hunt(UUID hunter,String targetName,long end){}
    private record Deban(String name,long end,Location location){}
}
