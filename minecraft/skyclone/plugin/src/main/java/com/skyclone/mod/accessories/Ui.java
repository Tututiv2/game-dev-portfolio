package com.skyclone.mod.accessories;

/** The module's menus, so each can open the others without a constructor cycle. */
final class Ui {
    final BagMenus bag;
    final PowerMenus powers;
    final EquipMenu equipment;
    final ToolMenus tools;
    /** Wave 5: the Carnival Mask Bag / New Year Cake Bag storage. */
    final KeepBags keep;

    Ui(BagMenus bag, PowerMenus powers, EquipMenu equipment, ToolMenus tools, KeepBags keep) {
        this.bag = bag;
        this.powers = powers;
        this.equipment = equipment;
        this.tools = tools;
        this.keep = keep;
        bag.wire(this);
        powers.wire(this);
        equipment.wire(this);
        tools.wire(this);
    }
}
