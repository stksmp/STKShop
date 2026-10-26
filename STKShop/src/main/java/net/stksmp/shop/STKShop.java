package net.stksmp.shop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.text.DecimalFormat;
import java.util.*;

public final class STKShop extends JavaPlugin implements Listener {
    private Economy economy;
    private NamespacedKey pickaxeKey;
    private final Map<UUID, SellSession> sellSessions = new HashMap<>();
    private final DecimalFormat moneyFormat = new DecimalFormat("#,##0.##");

    @Override public void onEnable() {
        saveDefaultConfig();
        pickaxeKey = new NamespacedKey(this, "pickaxe_3x3");
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("STKShop enabled. Vault economy: " + (economy != null));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stkshop")) {
            if (!sender.hasPermission("stkshop.admin")) { sender.sendMessage(color("&cNo permission.")); return true; }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) { reloadConfig(); sender.sendMessage(color("&bSTKShop config reloaded.")); }
            else sender.sendMessage(color("&eUse /stkshop reload"));
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        if (command.getName().equalsIgnoreCase("shop")) openMain(player);
        else if (command.getName().equalsIgnoreCase("sell")) openSell(player);
        return true;
    }

    private void openMain(Player p) {
        Inventory inv = Bukkit.createInventory(new MenuHolder("main", null), 45, color(getConfig().getString("settings.title", "&b&lSTK &fShop")));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        ConfigurationSection cats = getConfig().getConfigurationSection("settings.categories");
        if (cats != null) for (String key : cats.getKeys(false)) {
            ConfigurationSection c = cats.getConfigurationSection(key); if (c == null) continue;
            int slot = c.getInt("slot", 22); if (slot < 0 || slot >= inv.getSize()) continue;
            inv.setItem(slot, makeItem(c.getString("material", "CHEST"), 1, c.getString("name", key), c.getStringList("lore"), null, false));
        }
        inv.setItem(40, makeItem("AMETHYST_SHARD", 1, "&d&lShards", getConfig().getStringList("settings.shards-lore"), null, false));
        inv.setItem(44, makeItem("HOPPER", 1, "&a&lSell Items", List.of("&7Open the sell chest", "&eClick to sell farm drops"), null, false));
        p.openInventory(inv);
    }

    private void openCategory(Player p, String category) {
        ConfigurationSection c = getConfig().getConfigurationSection("settings.categories." + category);
        if (c == null) { openMain(p); return; }
        Inventory inv = Bukkit.createInventory(new MenuHolder("category", category), 54, color("&8Shop » " + c.getString("name", category)));
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        List<?> items = c.getList("items", List.of()); int slot = 10;
        for (Object obj : items) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            String material = String.valueOf(map.containsKey("material") ? map.get("material") : "STONE");
            int amount = parseInt(map.get("amount"), 1); String name = String.valueOf(map.containsKey("name") ? map.get("name") : material);
            List<String> lore = toStringList(map.get("lore")); double price = parseDouble(map.get("price"), 0);
            String enchant = map.get("enchant") == null ? null : String.valueOf(map.get("enchant"));
            boolean pickaxe = Boolean.parseBoolean(String.valueOf(map.containsKey("pickaxe3x3") ? map.get("pickaxe3x3") : false));
            if (slot >= 44) break;
            ItemStack item = makeItem(material, Math.max(1, Math.min(64, amount)), name, lore, enchant, pickaxe);
            ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.getPersistentDataContainer().set(new NamespacedKey(this, "price"), PersistentDataType.DOUBLE, price); meta.getPersistentDataContainer().set(new NamespacedKey(this, "shop_category"), PersistentDataType.STRING, category); item.setItemMeta(meta); }
            inv.setItem(slot, item); slot++; if (slot == 17 || slot == 26 || slot == 35) slot += 2;
        }
        inv.setItem(45, makeItem("ARROW", 1, "&bBack to Shop", List.of("&7Return to categories"), null, false));
        p.openInventory(inv);
    }

    private void openSell(Player p) {
        Inventory inv = Bukkit.createInventory(new MenuHolder("sell", null), 54, color(getConfig().getString("settings.sell-title", "&8Sell Items")));
        ItemStack button = makeItem(getConfig().getString("settings.sell-button-material", "LIME_STAINED_GLASS_PANE"), 1,
                getConfig().getString("settings.sell-button-name", "&a&lSELL ITEMS"), getConfig().getStringList("settings.sell-button-lore"), null, false);
        int buttonSlot = Math.max(0, Math.min(53, getConfig().getInt("settings.sell-button-slot", 53)));
        inv.setItem(buttonSlot, button);
        sellSessions.put(p.getUniqueId(), new SellSession(inv, buttonSlot));
        p.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !(e.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        Inventory top = e.getView().getTopInventory(); int raw = e.getRawSlot();
        if (holder.type.equals("sell")) {
            SellSession session = sellSessions.get(p.getUniqueId());
            if (raw < 0) return;
            if (raw < top.getSize() && raw == (session == null ? 53 : session.buttonSlot)) {
                e.setCancelled(true); sellItems(p, top, session == null ? 53 : session.buttonSlot); return;
            }
            if (raw < top.getSize()) {
                if (e.getClick().isShiftClick() || e.getClick().isKeyboardClick() || e.getClick().isCreativeAction()) e.setCancelled(true);
                return;
            }
            // Shift-click from player inventory into sell inventory is allowed; the button slot is protected.
            if (e.isShiftClick()) {
                e.setCancelled(true);
                ItemStack moving = e.getCurrentItem(); if (moving == null || moving.getType().isAir()) return;
                int free = firstFreeSellSlot(top, session == null ? 53 : session.buttonSlot);
                if (free >= 0) { top.setItem(free, moving.clone()); e.setCurrentItem(null); }
            }
            return;
        }
        e.setCancelled(true);
        if (raw < 0 || raw >= top.getSize()) return;
        ItemStack clicked = e.getCurrentItem(); if (clicked == null || clicked.getType().isAir()) return;
        if (holder.type.equals("main")) {
            if (raw == 40) { p.sendMessage(color("&dShard store: &f" + getConfig().getString("settings.shards-url", "https://stksmp.github.io/store"))); p.sendMessage(color("&7Copy the link above into your browser. You can also earn shards at the AFK zone.")); return; }
            if (raw == 44) { openSell(p); return; }
            ConfigurationSection cats = getConfig().getConfigurationSection("settings.categories");
            if (cats != null) for (String key : cats.getKeys(false)) if (cats.getConfigurationSection(key) != null && cats.getInt(key + ".slot", -1) == raw) { openCategory(p, key); return; }
        } else if (holder.type.equals("category")) {
            if (raw == 45) { openMain(p); return; }
            ItemMeta meta = clicked.getItemMeta(); if (meta == null) return;
            Double price = meta.getPersistentDataContainer().get(new NamespacedKey(this, "price"), PersistentDataType.DOUBLE);
            if (price == null) return;
            if (economy == null) { p.sendMessage(color("&cShop purchases require Vault and an economy plugin (such as EssentialsX).")); return; }
            if (economy.getBalance(p) < price) { p.sendMessage(color("&cYou need $" + moneyFormat.format(price) + " to buy that.")); return; }
            ItemStack purchase = clicked.clone(); purchase.setAmount(Math.max(1, clicked.getAmount()));
            ItemMeta pm = purchase.getItemMeta(); if (pm != null) { pm.getPersistentDataContainer().remove(new NamespacedKey(this, "price")); pm.getPersistentDataContainer().remove(new NamespacedKey(this, "shop_category")); purchase.setItemMeta(pm); }
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(purchase);
            if (!leftover.isEmpty()) { p.sendMessage(color("&cYour inventory is full.")); return; }
            economy.withdrawPlayer(p, price); p.sendMessage(color("&aPurchased for &f$" + moneyFormat.format(price) + "&a."));
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (!holder.type.equals("sell")) { e.setCancelled(true); return; }
        SellSession s = sellSessions.get(e.getWhoClicked().getUniqueId());
        int button = s == null ? 53 : s.buttonSlot;
        if (e.getRawSlots().contains(button)) e.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !(e.getView().getTopInventory().getHolder() instanceof MenuHolder holder) || !holder.type.equals("sell")) return;
        SellSession s = sellSessions.remove(p.getUniqueId()); if (s == null) return;
        Inventory inv = e.getView().getTopInventory();
        for (int i = 0; i < inv.getSize(); i++) if (i != s.buttonSlot) {
            ItemStack item = inv.getItem(i); if (item == null || item.getType().isAir()) continue;
            Map<Integer, ItemStack> rest = p.getInventory().addItem(item);
            for (ItemStack leftover : rest.values()) p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
    }

    private void sellItems(Player p, Inventory inv, int buttonSlot) {
        if (economy == null) { p.sendMessage(color("&cSelling requires Vault and an economy plugin (such as EssentialsX).")); return; }
        ConfigurationSection prices = getConfig().getConfigurationSection("settings.sell-prices"); double total = 0; int count = 0;
        if (prices == null) { p.sendMessage(color("&cNo sell prices are configured.")); return; }
        for (int i = 0; i < inv.getSize(); i++) if (i != buttonSlot) {
            ItemStack item = inv.getItem(i); if (item == null || item.getType().isAir()) continue;
            double unit = prices.getDouble(item.getType().name(), 0);
            if (unit > 0) { total += unit * item.getAmount(); count += item.getAmount(); inv.setItem(i, null); }
        }
        if (total <= 0) { p.sendMessage(color("&cNo sellable items found. Add prices in plugins/STKShop/config.yml.")); return; }
        economy.depositPlayer(p, total); p.sendMessage(color("&aSold &f" + count + " &aitems for &f$" + moneyFormat.format(total) + "&a!"));
        try { p.playSound(p.getLocation(), Sound.valueOf(getConfig().getString("settings.sell-sound", "ENTITY_EXPERIENCE_ORB_PICKUP")), 1f, 1f); } catch (IllegalArgumentException ignored) {}
    }

    @EventHandler public void on3x3Break(BlockBreakEvent e) {
        Player p = e.getPlayer(); ItemStack tool = p.getInventory().getItemInMainHand(); ItemMeta meta = tool.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(pickaxeKey, PersistentDataType.BYTE) || e.isCancelled()) return;
        Block center = e.getBlock(); Vector direction = p.getLocation().getDirection();
        List<Block> targets = new ArrayList<>();
        // Mine a 3x3 plane perpendicular to the player's look direction.
        if (Math.abs(direction.getY()) > 0.65) {
            for (int x=-1;x<=1;x++) for (int z=-1;z<=1;z++) if (x!=0 || z!=0) targets.add(center.getRelative(x,0,z));
        } else if (Math.abs(direction.getX()) > Math.abs(direction.getZ())) {
            for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++) if (y!=0 || z!=0) targets.add(center.getRelative(0,y,z));
        } else {
            for (int x=-1;x<=1;x++) for (int y=-1;y<=1;y++) if (x!=0 || y!=0) targets.add(center.getRelative(x,y,0));
        }
        for (Block b : targets) {
            if (b.getType().isAir() || b.getType().getHardness() < 0 || !p.getWorld().getWorldBorder().isInside(b.getLocation())) continue;
            if (b.getType() == Material.BEDROCK || b.getType() == Material.END_PORTAL_FRAME || b.getType() == Material.END_PORTAL || b.getType() == Material.NETHER_PORTAL) continue;
            b.breakNaturally(tool, true);
        }
    }

    private ItemStack makeItem(String materialName, int amount, String name, List<String> lore, String enchantText, boolean pickaxe3x3) {
        Material mat = Material.matchMaterial(materialName); if (mat == null) mat = Material.CHEST;
        ItemStack item = new ItemStack(mat, Math.max(1, Math.min(64, amount))); ItemMeta meta = item.getItemMeta(); if (meta == null) return item;
        meta.setDisplayName(color(name)); if (lore != null && !lore.isEmpty()) meta.setLore(lore.stream().map(this::color).toList());
        if (enchantText != null && !enchantText.isBlank()) {
            String[] pair = enchantText.split(":"); if (pair.length == 2) { Enchantment ench = enchantByName(pair[0]); int level = parseInt(pair[1], 1); if (ench != null) { if (meta instanceof EnchantmentStorageMeta book) book.addStoredEnchant(ench, level, true); else meta.addEnchant(ench, level, true); } }
        }
        if (pickaxe3x3 && (mat == Material.DIAMOND_PICKAXE || mat == Material.NETHERITE_PICKAXE)) {
            meta.addEnchant(Enchantment.EFFICIENCY, 5, true); meta.addEnchant(Enchantment.UNBREAKING, 3, true); meta.addEnchant(Enchantment.FORTUNE, 3, true); meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.getPersistentDataContainer().set(pickaxeKey, PersistentDataType.BYTE, (byte)1);
            List<String> l = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>(); l.add(color("&bSpecial ability: &f3x3 mining")); meta.setLore(l);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES); item.setItemMeta(meta); return item;
    }
    private Enchantment enchantByName(String n) { String key = n.toUpperCase(Locale.ROOT); return switch (key) { case "EFFICIENCY" -> Enchantment.EFFICIENCY; case "FORTUNE" -> Enchantment.FORTUNE; case "UNBREAKING" -> Enchantment.UNBREAKING; case "MENDING" -> Enchantment.MENDING; case "SHARPNESS" -> Enchantment.SHARPNESS; case "PROTECTION" -> Enchantment.PROTECTION; default -> null; }; }
    private void fill(Inventory inv, Material mat) { ItemStack pane = new ItemStack(mat); ItemMeta m = pane.getItemMeta(); if (m != null) { m.setDisplayName(" "); pane.setItemMeta(m); } for (int i=0;i<inv.getSize();i++) inv.setItem(i,pane); }
    private int firstFreeSellSlot(Inventory inv, int button) { for (int i=0;i<inv.getSize();i++) if (i != button && (inv.getItem(i)==null || inv.getItem(i).getType().isAir())) return i; return -1; }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private int parseInt(Object o, int d) { try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ex) { return d; } }
    private double parseDouble(Object o, double d) { try { return Double.parseDouble(String.valueOf(o)); } catch (Exception ex) { return d; } }
    private List<String> toStringList(Object obj) { if (!(obj instanceof List<?> list)) return List.of(); List<String> out = new ArrayList<>(); for (Object x : list) out.add(String.valueOf(x)); return out; }
    private static final class MenuHolder implements org.bukkit.inventory.InventoryHolder { final String type; final String category; MenuHolder(String type, String category) { this.type=type; this.category=category; } @Override public Inventory getInventory() { return null; } }
    private record SellSession(Inventory inventory, int buttonSlot) {}
}
