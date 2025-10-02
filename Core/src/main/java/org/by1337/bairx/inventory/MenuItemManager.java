package org.by1337.bairx.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.by1337.bairx.BAirDropX;
import org.by1337.bairx.airdrop.AirDrop;
import org.by1337.bairx.inventory.item.InventoryItem;
import org.by1337.bairx.menu.AsyncClickListener;
import org.by1337.bairx.menu.ItemBuilder;

import java.util.HashMap;
import java.util.Map;

public class MenuItemManager extends AsyncClickListener {
    private final int itemSlots = 45;
    private final InventoryManager inventoryManager;
    private final AirDrop airDrop;
    private final Player player;
    private int page = 0;
    private Map<Integer, InventoryItem> itemsOnScreen;
    private Mode currentMode = Mode.ADD_ITEMS;

    private enum Mode {
        ADD_ITEMS("&7Добавление предметов"),
        EDIT_CHANCE("&7Редактирование шансов");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    public MenuItemManager(InventoryManager inventoryManager, AirDrop airDrop, Player player) {
        super(player, false);
        this.inventoryManager = inventoryManager;
        this.airDrop = airDrop;
        this.player = player;
        createInventory(54, BAirDropX.getMessage().messageBuilder(currentMode.getTitle()));
        generate();
    }

    private void generate() {
        inventory.clear();

        inventory.setItem(45, new ItemBuilder()
                .material(Material.CHEST)
                .name("&6Режим: " + (currentMode == Mode.ADD_ITEMS ? "Добавление" : "Редактирование"))
                .lore("&7ЛКМ - Переключить режим")
                .build());

        inventory.setItem(46, new ItemBuilder()
                .material(Material.ARROW)
                .name("&cНазад")
                .lore("&7Предыдущая страница")
                .build());
        inventory.setItem(52, new ItemBuilder()
                .material(Material.ARROW)
                .name("&aВперёд")
                .lore("&7Следующая страница")
                .build());

        inventory.setItem(53, new ItemBuilder()
                .material(Material.BARRIER)
                .name("&cСохранить и выйти")
                .lore("&7Сохранить изменения и закрыть меню")
                .build());

        int startPos = itemSlots * page;
        var list = inventoryManager.getItems();
        itemsOnScreen = new HashMap<>();

        for (int i = startPos, slot = 0; i < startPos + itemSlots; i++, slot++) {
            if (list.size() <= i) break;
            var item = list.get(i);
            itemsOnScreen.put(slot, item);

            if (currentMode == Mode.ADD_ITEMS) {
                inventory.setItem(slot, item.getItemStack());
            } else {
                inventory.setItem(slot, new ItemBuilder()
                        .lore(
                                "&aШанс появления:&f {chance}",
                                "&aРандомизация количества:&f {enable-random-count}",
                                "&aМинимальное количество:&f {min-count}",
                                "&aМаксимальное количество:&f {max-count}",
                                "",
                                "&aЛКМ&f - +1 к шансу появления",
                                "&aShift + ЛКМ&f - +10 к шансу появления",
                                "&aПКМ&f - -1 к шансу появления",
                                "&aShift + ПКМ&f - -10 к шансу появления",
                                "&aQ&f - Включить/выключить рандомизацию количества",
                                "&a1&f - +1 к минимальному количеству",
                                "&a2&f - +10 к минимальному количеству",
                                "&c3&f - -1 к минимальному количеству",
                                "&c4&f - -10 к минимальному количеству",
                                "&a5&f - +1 к максимальному количеству",
                                "&a6&f - +10 к максимальному количеству",
                                "&c7&f - -1 к максимальному количеству",
                                "&c8&f - -10 к максимальному количеству"
                        )
                        .replaceLore(item::replace)
                        .build(item.getItemStack())
                );
            }
        }
    }

    private void updateItems() {
        for (int i = 0; i < itemSlots; i++) {
            var itemStack = inventory.getItem(i);
            InventoryItem item = itemsOnScreen.get(i);

            if (item == null) {
                if (itemStack != null && !itemStack.getType().isAir()) {
                    inventoryManager.getItems().add(new InventoryItem(itemStack, 100, false, 1, 64));
                }
            } else {
                if (itemStack == null || itemStack.getType().isAir()) {
                    inventoryManager.getItems().remove(item);
                } else if (!item.getItemStack().equals(itemStack)) {
                    inventoryManager.getItems().remove(item);
                    inventoryManager.getItems().add(new InventoryItem(itemStack, item.getChance(),
                            item.isRandomAmount(), item.getMinAmount(), item.getMaxAmount()));
                }
            }
        }
        generate();
    }

    @Override
    protected void onClose(InventoryCloseEvent e) {
        if (currentMode == Mode.ADD_ITEMS) {
            updateItems();
        }
        inventoryManager.sortItems();
        airDrop.trySave();
    }

    @Override
    protected void onClick(InventoryClickEvent e) {
        int slot = e.getSlot();

        if (slot >= 45 && slot <= 53) {
            e.setCancelled(true);
            handleControlClick(slot, e.getClick());
            return;
        }

        if (slot < itemSlots) {
            if (currentMode == Mode.ADD_ITEMS) {
                e.setCancelled(false);
                syncUtil(this::updateItems, 1);
            } else {
                e.setCancelled(true);
                handleItemEditClick(slot, e.getClick(), e.getHotbarButton());
            }
        }
    }

    private void handleControlClick(int slot, org.bukkit.event.inventory.ClickType click) {
        switch (slot) {
            case 45:
                currentMode = (currentMode == Mode.ADD_ITEMS) ? Mode.EDIT_CHANCE : Mode.ADD_ITEMS;
                generate();
                break;

            case 46:
                if (page > 0) {
                    page--;
                    generate();
                }
                break;

            case 52:
                page++;
                generate();
                break;

            case 53:
                player.closeInventory();
                break;
        }
    }

    private void handleItemEditClick(int slot, org.bukkit.event.inventory.ClickType click, int hotbarButton) {
        var item = itemsOnScreen.get(slot);
        if (item == null) return;

        switch (click) {
            case LEFT -> {
                int chance = item.getChance() + 1;
                item.setChance(Math.min(chance, 100));
                generate();
            }
            case SHIFT_LEFT -> {
                int chance = item.getChance() + 10;
                item.setChance(Math.min(chance, 100));
                generate();
            }
            case RIGHT -> {
                int chance = item.getChance() - 1;
                item.setChance(Math.max(chance, 0));
                generate();
            }
            case SHIFT_RIGHT -> {
                int chance = item.getChance() - 10;
                item.setChance(Math.max(chance, 0));
                generate();
            }
            case DROP -> {
                item.setRandomAmount(!item.isRandomAmount());
                generate();
            }
            case NUMBER_KEY -> {
                handleNumberKeyEdit(item, hotbarButton);
                generate();
            }
        }
    }

    private void handleNumberKeyEdit(InventoryItem item, int hotbarButton) {
        switch (hotbarButton) {
            case 0 -> item.setMinAmount(Math.max(1, item.getMinAmount() + 1));
            case 1 -> item.setMinAmount(Math.max(1, item.getMinAmount() + 10));
            case 2 -> item.setMinAmount(Math.max(1, item.getMinAmount() - 1));
            case 3 -> item.setMinAmount(Math.max(1, item.getMinAmount() - 10));
            case 4 -> item.setMaxAmount(Math.min(64, item.getMaxAmount() + 1));
            case 5 -> item.setMaxAmount(Math.min(64, item.getMaxAmount() + 10));
            case 6 -> item.setMaxAmount(Math.min(64, item.getMaxAmount() - 1));
            case 7 -> item.setMaxAmount(Math.min(64, item.getMaxAmount() - 10));
        }
        if (item.getMinAmount() > item.getMaxAmount()) {
            item.setMinAmount(item.getMaxAmount());
        }
    }

    @Override
    protected void onClick(InventoryDragEvent e) {
        if (currentMode == Mode.ADD_ITEMS) {
            if (e.getRawSlots().stream().anyMatch(slot -> slot >= 45 && slot <= 53)) {
                e.setCancelled(true);
                return;
            }
            e.setCancelled(false);
            syncUtil(this::updateItems, 1);
        } else {
            e.setCancelled(true);
        }
    }

    public Inventory getInventory() {
        return inventory;
    }
}
