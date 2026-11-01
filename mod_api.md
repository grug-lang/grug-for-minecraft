Generated from mod_api.json by mod_api_catalog.py; do not edit. Descriptions are in mod_api.json.

# Entities

## Block
export display_tick(ctx: DisplayTickContext, world: Level, x: number, y: number, z: number)
export init(ctx: BlockInitContext)
export on_break(world: Level, x: number, y: number, z: number)
export shape(ctx: ShapeContext, world: Level, x: number, y: number, z: number)
export use(ctx: BlockUseContext, world: Level, x: number, y: number, z: number, player: Player)

## BlockEntity
export add_item(item: Item, damage: number, count: number, from_side: number, add: bool, response: AddResponse)
export extract_item(from_side: number, remove: bool, response: ExtractResponse)
export init(ctx: BlockEntityInitContext)
export item_extracted(slot: number, amount: number)
export item_inserted(slot: number, amount: number)
export on_neighbor_change()
export output_taken(amount: number)
export render(ctx: RenderContext)
export render_frame(ctx: RenderFrameContext)
export tick()
.add_item(item: Item, damage: number, count: number, from_side: number, add: bool) number
.append_saved_list_record(name: string) number
.ask_extract_item(from_side: number) number
.block_pos() BlockPos
.consume_crafting_ingredients(start_slot: number)
.count_item_in_inventory(item: Item, damage: number) number
.extract_energy(max: number) number
.extract_item_from_inventory(item: Item, damage: number, amount: number) number
.extract_item_into(destination: BlockEntity, destination_slot: number, from_side: number) number
.get_crafting_result_damage() number
.get_crafting_result_item() Option[Item]
.get_energy_stored() number
.get_inventory_size() number
.get_item_count_in_slot(slot: number) number
.get_item_damage_in_slot(slot: number) number
.get_item_in_slot(slot: number) Option[Item]
.get_saved_list_item(name: string, index: number) Option[Item]
.get_saved_list_number(name: string, index: number, key: string) number
.get_saved_list_size(name: string) number
.get_saved_number(name: string) number
.insert_item_into_inventory(item: Item, damage: number, amount: number, insert: bool) number
.is_power_receiver() bool
.is_slot_extractable(slot: number) bool
.is_slot_insertable(slot: number) bool
.level() Level
.receive_energy(amount: number) number
.redraw()
.remove_saved_list_record(name: string, index: number)
.set_item_count_in_slot(slot: number, count: number)
.set_item_damage_in_slot(slot: number, damage: number)
.set_item_in_slot(slot: number, item: Item, count: number)
.set_saved_list_item(name: string, index: number, item: Item)
.set_saved_list_number(name: string, index: number, key: string, value: number)
.set_saved_number(name: string, value: number)
.take_crafting_result(amount: number) number
.take_item_from_slot(slot: number, amount: number) number
.update_recipe_output(start_slot: number)

## Item
export init()

## Overlay
export click(ctx: OverlayClickContext)
export key(ctx: OverlayKeyContext)
export render(ctx: OverlayRenderContext)
export scroll(ctx: OverlayScrollContext)

## Test
export init(ctx: TestInitContext)
export tick(ctx: TestTickContext)
Test.block_collision(level: Level, x: number, y: number, z: number, x1: number, y1: number, z1: number, x2: number, y2: number, z2: number) bool
Test.block_ray_trace(level: Level, x: number, y: number, z: number, start_x: number, start_y: number, start_z: number, end_x: number, end_y: number, end_z: number) bool
Test.block_selection(level: Level, x: number, y: number, z: number, point_x: number, point_y: number, point_z: number) bool
Test.clear_carried_item()
Test.clear_held_item(player: Player)
Test.click_screen(x: number, y: number, button: number) bool
Test.close_screen()
Test.display_tick(level: Level, x: number, y: number, z: number, seed: number) number
Test.force_gc()
Test.get_carried_item() Option[Item]
Test.get_client_level() Level
Test.get_held_count(player: Player) number
Test.get_item_max_stack(item: Item) number
Test.get_origin() Vec3
Test.get_player() Player
Test.gui_height() number
Test.gui_left() number
Test.gui_top() number
Test.gui_width() number
Test.high_block_round_trip() number
Test.hot_reload_count(relative_path: string) number
Test.is_reference() bool
Test.load_nbt(block_entity: BlockEntity)
Test.loader_name() string
Test.mining_crack(level: Level, x: number, y: number, z: number) bool
Test.notify_neighbors(level: Level, x: number, y: number, z: number)
Test.open_inventory(player: Player)
Test.overlay_drawn() bool
Test.overlay_supported() bool
Test.particle_position(index: number) Vec3
Test.press_screen_key(key: number, ctrl: bool, release: bool) bool
Test.registers_mod_recipes() bool
Test.reload_count(relative_path: string) number
Test.round_trip_nbt(block_entity: BlockEntity)
Test.screen_open() bool
Test.screen_width() number
Test.screenshot() Screenshot
Test.scroll_screen(x: number, y: number, delta: number) bool
Test.set_box_radius(radius: number)
Test.set_carried_item(item: Item)
Test.set_held_item(player: Player, item: Item)
Test.set_mod_file_state(relative_path: string, state: string)
Test.set_nbt_item(block_entity: BlockEntity, path: string, item: Item)
Test.set_nbt_number(block_entity: BlockEntity, path: string, type: string, value: number)
Test.stall(milliseconds: number)
Test.tick_block_entity(block_entity: BlockEntity, ticks: number)
Test.tick_level(level: Level, ticks: number) number
Test.use_block(level: Level, x: number, y: number, z: number)
Test.world_settled() bool

# Classes

## AddResponse
.accept_into_slot(slot: number)
.refuse()

## BlockEntityInitContext
.set_extractable_slots(first: number, last: number)
.set_insertable_slots(first: number, last: number)
.set_power_receiver(max_stored: number)

## BlockInitContext
.set_block_entity(entity_string: entity)
.set_custom_render()
.set_display_tick()
.set_flat_lighting()
.set_frame_render()
.set_hardness(value: number)
.set_inventory_size(size: number)
.set_material(material_name: string)
.set_shape()

## BlockPos
.above_n(n: number) BlockPos
.center() Vec3

## BlockUseContext
.decline_use()

## Color
Color.rgb(r: number, g: number, b: number) Color

## DisplayTickContext
.add_particle(name: string, x: number, y: number, z: number, vx: number, vy: number, vz: number)
.random() number

## Entity
.get_delta_movement() Vec3
.set_delta_movement(vec3: Vec3)
.spawn(level: Level)

## ExtractResponse
.give_crafting_result()
.give_crafting_result_consumed()
.give_from_slot(slot: number, amount: number)
.refuse()

## GUI
.add_crafting_grid(start_slot: number, x: number, y: number)
.add_crafting_result(x: number, y: number)
.add_player_inventory(main_x: number, main_y: number, hotbar_x: number, hotbar_y: number)
.add_text(text: string, x: number, y: number, color: Color)
.open(player: Player, block_entity: BlockEntity)

## ItemEntity
.count() number
.damage() number
.entity() Entity
.pickup_delay() number
.set_pickup_delay(ticks: number)

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
.random_float() number
.random_gaussian() number
.random_int(bound: number) number

## Math
Math.floor(value: number) number
Math.remainder(value: number, divisor: number) number

## Option
.has() bool
.set(value: $Value)
.unwrap() $Value
Option.new() Option[$Value]

## OverlayClickContext
.button() number
.carried_item() Option[Item]
.delete_carried_item()
.gui_height() number
.gui_left() number
.gui_top() number
.gui_width() number
.mouse_x() number
.mouse_y() number
.screen_height() number
.screen_width() number
.shift() bool
.take()

## OverlayKeyContext
.ctrl() bool
.key() number
.take()

## OverlayRenderContext
.draw_box(x1: number, y1: number, x2: number, y2: number)
.draw_item(item: Item, x: number, y: number)
.gui_height() number
.gui_left() number
.gui_top() number
.gui_width() number
.mouse_x() number
.mouse_y() number
.screen_height() number
.screen_width() number

## OverlayScrollContext
.delta() number
.gui_height() number
.gui_left() number
.gui_top() number
.gui_width() number
.mouse_x() number
.mouse_y() number
.screen_height() number
.screen_width() number
.take()

## Player
.get_held_item() Option[Item]

## RenderContext
.draw_box(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)
.draw_model(model: resource, x: number, y: number, z: number, dx: number, dy: number, dz: number)

## RenderFrameContext
.camera_yaw() number
.draw_item(item: Item, damage: number, count: number, x: number, y: number, z: number)
.draw_model(model: resource, x: number, y: number, z: number, dx: number, dy: number, dz: number)
.partial_tick() number

## Screenshot
.capture(x1: number, y1: number, x2: number, y2: number) Screenshot
.differs_from(other: Screenshot, percent: number)
.equals(reference: resource, x1: number, y1: number, x2: number, y2: number)
.tolerance(percent: number) Screenshot

## ShapeContext
.add_collision_box(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)
.add_selection_box(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)

## TestInitContext
.set_max_ticks(ticks: number)

## TestTickContext
.assert(condition: bool, message: string)
.current_tick() number
.expect_error(message: string)
.not_done()

## Vec3
.x() number
.y() number
.z() number
Vec3.new(x: number, y: number, z: number) Vec3
Vec3.zero() Vec3

# Host functions

drop_inventory(level: Level, x: number, y: number, z: number)
equals(a: $Type, b: $Type) bool
give_item(item: Item)
give_item_stack(item: Item)
gui(texture_path: resource) GUI
has_sound(sound: string) bool
item(resource_location: ResourceLocation) Item
item_at(index: number) Item
item_count() number
item_entity(level: Level, x: number, y: number, z: number, item_stack: ItemStack) ItemEntity
item_stack(item: Item, damage: number, count: number) ItemStack
play_ui_sound(sound: string, volume: number, pitch: number)
print(value: $Value)
print2(a: $Value1, b: $Value2)
print3(a: $Value1, b: $Value2, c: $Value3)
resource_location(resource_location_string: string) ResourceLocation
