Generated from mod_api.json by mod_api_catalog.py; do not edit. Descriptions are in mod_api.json.

# Entities

## Block
export init()
export on_break(world: Level, x: number, y: number, z: number)
export use(world: Level, x: number, y: number, z: number, player: Player)

## BlockEntity
export init()
export item_extracted(slot: number, amount: number)
export item_inserted(slot: number, amount: number)
export on_neighbor_change()
export output_taken(amount: number)
export render()
export tick()

## Item
export init()

## Test
export run()
Test.assert(condition: bool, message: string)
Test.close_screen()
Test.expect_error(message: string)
Test.force_gc()
Test.get_client_level() Level
Test.get_origin() Vec3
Test.get_player() Player
Test.hot_reload_count(relative_path: string) number
Test.is_reference() bool
Test.not_done()
Test.notify_neighbors(level: Level, x: number, y: number, z: number)
Test.registers_mod_recipes() bool
Test.reload_count(relative_path: string) number
Test.round_trip_nbt(block_entity: BlockEntity)
Test.screen_open() bool
Test.screenshot() Screenshot
Test.set_box_radius(radius: number)
Test.set_mod_file_state(relative_path: string, state: string)
Test.setup_graphics_camera() Vec3
Test.tick() number
Test.tick_block_entity(block_entity: BlockEntity, ticks: number)
Test.use_block(level: Level, x: number, y: number, z: number)
Test.world_settled() bool

# Classes

## BlockPos
.above_n(n: number) BlockPos
.center() Vec3

## Color
Color.rgb(r: number, g: number, b: number) Color

## Entity
.set_delta_movement(vec3: Vec3)
.spawn(level: Level)

## GUI
.add_crafting_grid(start_slot: number, x: number, y: number)
.add_crafting_result(x: number, y: number)
.add_player_inventory(main_x: number, main_y: number, hotbar_x: number, hotbar_y: number)
.add_text(text: string, x: number, y: number, color: Color)
.open(player: Player, block_entity: BlockEntity)

## ItemEntity
.damage() number
.entity() Entity

## Level
.count_item_entities(x: number, y: number, z: number, radius: number) number
.explode(x: number, y: number, z: number, power: number)
.find_item_entity(x: number, y: number, z: number, radius: number) Option[ItemEntity]
.get_block(x: number, y: number, z: number) string
.get_block_entity(x: number, y: number, z: number) Option[BlockEntity]
.get_time() number
.has_neighbor_signal(x: number, y: number, z: number) bool
.is_air(x: number, y: number, z: number) bool
.place_block(x: number, y: number, z: number, block_name: string)

## Math
Math.floor(value: number) number
Math.remainder(value: number, divisor: number) number

## Option
.has() bool
.set(value: $Value)
.unwrap() $Value
Option.new() Option[$Value]

## Screenshot
.capture(x1: number, y1: number, x2: number, y2: number) Screenshot
.differs_from(other: Screenshot, percent: number)
.equals(reference: resource, x1: number, y1: number, x2: number, y2: number)
.tolerance(percent: number) Screenshot

## Vec3
.x() number
.y() number
.z() number

# Host functions

consume_crafting_ingredients(block_entity: BlockEntity, start_slot: number)
count_item_in_inventory(block_entity: BlockEntity, item: Item, damage: number) number
draw_box(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)
draw_model(model: resource, x: number, y: number, z: number)
drop_inventory(level: Level, x: number, y: number, z: number)
equals(a: $Type, b: $Type) bool
extract_energy(block_entity: BlockEntity, max: number) number
extract_item_from_inventory(block_entity: BlockEntity, item: Item, damage: number, amount: number) number
get_block_entity_level(block_entity: BlockEntity) Level
get_block_pos_of_block_entity(block_entity: BlockEntity) BlockPos
get_crafting_result_damage(block_entity: BlockEntity) number
get_crafting_result_item(block_entity: BlockEntity) Option[Item]
get_energy_stored(block_entity: BlockEntity) number
get_inventory_size(block_entity: BlockEntity) number
get_item_count_in_slot(block_entity: BlockEntity, slot: number) number
get_item_damage_in_slot(block_entity: BlockEntity, slot: number) number
get_item_in_slot(block_entity: BlockEntity, slot: number) Option[Item]
get_saved_number(block_entity: BlockEntity, name: string) number
gui(texture_path: resource) GUI
insert_item_into_inventory(block_entity: BlockEntity, item: Item, damage: number, amount: number) number
is_power_receiver(block_entity: BlockEntity) bool
is_slot_extractable(block_entity: BlockEntity, slot: number) bool
is_slot_insertable(block_entity: BlockEntity, slot: number) bool
item(resource_location: ResourceLocation) Item
item_entity(level: Level, x: number, y: number, z: number, item_stack: ItemStack) ItemEntity
item_stack(item: Item, damage: number) ItemStack
print(value: $Value)
print2(a: $Value1, b: $Value2)
print3(a: $Value1, b: $Value2, c: $Value3)
receive_energy(block_entity: BlockEntity, amount: number) number
redraw(block_entity: BlockEntity)
resource_location(resource_location_string: string) ResourceLocation
set_block_entity(entity_string: entity)
set_custom_render()
set_extractable_slots(block_entity: BlockEntity, first: number, last: number)
set_flat_lighting()
set_hardness(value: number)
set_insertable_slots(block_entity: BlockEntity, first: number, last: number)
set_inventory_size(size: number)
set_item_count_in_slot(block_entity: BlockEntity, slot: number, count: number)
set_item_damage_in_slot(block_entity: BlockEntity, slot: number, damage: number)
set_item_in_slot(block_entity: BlockEntity, slot: number, item: Item, count: number)
set_material(material_name: string)
set_power_receiver(block_entity: BlockEntity, max_stored: number)
set_saved_number(block_entity: BlockEntity, name: string, value: number)
take_crafting_result(block_entity: BlockEntity, amount: number) number
take_item_from_slot(block_entity: BlockEntity, slot: number, amount: number) number
update_recipe_output(block_entity: BlockEntity, start_slot: number)
vec3_zero() Vec3
