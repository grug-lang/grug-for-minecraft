Generated from mod_api.json by mod_api_catalog.py; do not edit. Descriptions are in mod_api.json.

# Entities

## Block
export display_tick(ctx: DisplayTickContext, world: Level, x: number, y: number, z: number)
export init(ctx: BlockInitContext)
export on_break(world: Level, x: number, y: number, z: number)
export placed(world: Level, x: number, y: number, z: number, placer: Player)
export shape(ctx: ShapeContext, world: Level, x: number, y: number, z: number)
export use(ctx: BlockUseContext, world: Level, x: number, y: number, z: number, player: Player)

## BlockEntity
export add_item(item: Item, damage: number, count: number, from_side: number, add: bool, response: AddResponse)
export extract_item(from_side: number, remove: bool, response: ExtractResponse)
export get_liquid(response: LiquidResponse)
export get_liquid_amount(response: LiquidResponse)
export get_liquid_capacity(response: LiquidResponse)
export init(ctx: BlockEntityInitContext)
export item_extracted(slot: number, amount: number)
export item_inserted(slot: number, amount: number)
export offer_liquid(liquid: string, amount: number, from_side: number, fill: bool, response: LiquidResponse)
export on_neighbor_change()
export output_taken(amount: number)
export render(ctx: RenderContext)
export render_frame(ctx: RenderFrameContext)
export take_liquid(amount: number, take: bool, response: LiquidResponse)
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
.get_liquid() Option[string]
.get_liquid_amount() number
.get_liquid_capacity() number
.get_saved_list_item(name: string, index: number) Option[Item]
.get_saved_list_number(name: string, index: number, key: string) number
.get_saved_list_size(name: string) number
.get_saved_list_string(name: string, index: number) Option[string]
.get_saved_number(name: string) number
.get_saved_string(name: string) Option[string]
.insert_item_into_inventory(item: Item, damage: number, amount: number, insert: bool) number
.is_power_receiver() bool
.is_slot_extractable(slot: number) bool
.is_slot_insertable(slot: number) bool
.level() Level
.offer_liquid(liquid: string, amount: number, from_side: number, fill: bool) number
.receive_energy(amount: number) number
.redraw()
.remove_saved_list_record(name: string, index: number)
.set_item_count_in_slot(slot: number, count: number)
.set_item_damage_in_slot(slot: number, damage: number)
.set_item_in_slot(slot: number, item: Item, count: number)
.set_saved_list_item(name: string, index: number, item: Item)
.set_saved_list_number(name: string, index: number, key: string, value: number)
.set_saved_list_string(name: string, index: number, value: string)
.set_saved_number(name: string, value: number)
.set_saved_string(name: string, value: string)
.take_crafting_result(amount: number) number
.take_item_from_slot(slot: number, amount: number) number
.take_liquid(amount: number, take: bool) number
.update_recipe_output(start_slot: number)

## Item
export init()

## Overlay
export click(ctx: OverlayClickContext)
export key(ctx: OverlayKeyContext)
export render(ctx: OverlayRenderContext)
export render_hud(ctx: OverlayHudContext)
export render_world(ctx: OverlayWorldContext)
export screen_click(ctx: OverlayClickContext)
export screen_closed()
export screen_init(ctx: ScreenInitContext)
export screen_key(ctx: OverlayKeyContext)
export screen_list_row(ctx: ListRowResponse)
export screen_render(ctx: OverlayRenderContext)
export screen_scroll(ctx: OverlayScrollContext)
export screen_widget_changed(ctx: ScreenWidgetContext)
export scroll(ctx: OverlayScrollContext)
export world_binding(ctx: OverlayWorldBindingContext)
export world_key(ctx: OverlayWorldKeyContext)

## Test
export init(ctx: TestInitContext)
export tick(ctx: TestTickContext)
.is_reference() bool
.loader_name() string
.on_dedicated_server() bool
.overlay_supported() bool
.owned_screens_supported() bool
.placed_supported() bool
.registers_mod_recipes() bool
.screenshots_supported() bool
.text_field_typing_supported() bool
.world_overlay_supported() bool

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
.set_drops_nothing()
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
.replace_held_item(item: Item)

## Color
Color.rgb(r: number, g: number, b: number) Color
Color.rgba(r: number, g: number, b: number, a: number) Color

## Config
.bool(key: string) bool
.color(key: string) Color
.number(key: string) number

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

## File
.entry_count(path: string) number
.entry_name(path: string, index: number) string
.exists(path: string) bool
.read_text(path: string) Option[string]
.try_write_text(path: string, text: string) bool
.write_text(path: string, text: string)
File.open(root: resource) File

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
.count_item_entities_of(x: number, y: number, z: number, radius: number, item: Item) number
.count_mobs(category: string) number
.dimension() string
.explode(x: number, y: number, z: number, power: number)
.find_item_entity(x: number, y: number, z: number, radius: number) Option[ItemEntity]
.get_block(x: number, y: number, z: number) string
.get_block_entity(x: number, y: number, z: number) Option[BlockEntity]
.get_block_light(x: number, y: number, z: number) number
.get_hostile_block_light_limit() number
.get_mob_cap(category: string) number
.get_sky_light(x: number, y: number, z: number) number
.get_time() number
.has_mob_category(category: string) bool
.has_neighbor_signal(x: number, y: number, z: number) bool
.is_air(x: number, y: number, z: number) bool
.is_hostile_spawn_space(x: number, y: number, z: number) bool
.name() string
.place_block(x: number, y: number, z: number, block_name: string)
.random_float() number
.random_gaussian() number
.random_int(bound: number) number
.set_time(ticks: number)

## LiquidResponse
.answer_amount(amount: number)
.answer_liquid(liquid: string)
.answer_none()

## ListRowResponse
.index() number
.set_text(text: string)
.set_text_colour(color: Color)
.widget() Widget

## Math
Math.abs(value: number) number
Math.atan2(y: number, x: number) number
Math.ceil(value: number) number
Math.cos(angle: number) number
Math.cosh(value: number) number
Math.floor(value: number) number
Math.max(a: number, b: number) number
Math.min(a: number, b: number) number
Math.pi() number
Math.remainder(value: number, divisor: number) number
Math.signum(value: number) number
Math.sin(angle: number) number
Math.sinh(value: number) number
Math.sqrt(value: number) number
Math.tau() number
Math.toDegrees(radians: number) number

## Option
.has() bool
.set(value: $Value)
.unwrap() $Value
Option.new() Option[$Value]

## OverlayClickContext
.button() number
.carried_item() Option[Item]
.close_screen()
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

## OverlayHudContext
.add_text(text: string, x: number, y: number, color: Color)
.get_level() Level
.screen_height() number
.screen_width() number
.top_inset() number

## OverlayKeyContext
.character() number
.ctrl() bool
.key() number
.repeat() bool
.shift() bool
.take()

## OverlayRenderContext
.carried_item() Option[Item]
.draw_box(x1: number, y1: number, x2: number, y2: number)
.draw_item(item: Item, x: number, y: number)
.draw_text(text: string, x: number, y: number, color: Color)
.draw_tooltip(text: string, x: number, y: number)
.gui_height() number
.gui_left() number
.gui_top() number
.gui_width() number
.mouse_x() number
.mouse_y() number
.screen_height() number
.screen_serial() number
.screen_width() number
.text_width(text: string) number

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

## OverlayWorldBindingContext
.get_config() Config
.id() string

## OverlayWorldContext
.camera_x() number
.camera_y() number
.camera_z() number
.draw_line(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, color: Color)
.draw_triangle(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, x3: number, y3: number, z3: number, color: Color)
.get_config() Config
.get_level() Level
.get_spawn_squares(radius: number) SpawnSquares
.partial_tick() number
.player_x() number
.player_y() number
.player_z() number
.set_depth_test(enabled: bool)

## OverlayWorldKeyContext
.ctrl() bool
.key() number

## Player
.get_held_item() Option[Item]
.position() Vec3

## Random
.next_double() number
.next_float() number
.next_int(bound: number) number
Random.new() Random
Random.new_seeded(seed: number) Random

## RenderContext
.draw_box(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)
.draw_model(model: resource, x: number, y: number, z: number, dx: number, dy: number, dz: number)

## RenderFrameContext
.camera_yaw() number
.draw_item(item: Item, damage: number, count: number, x: number, y: number, z: number)
.draw_liquid(liquid: string, x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)
.draw_model(model: resource, x: number, y: number, z: number, dx: number, dy: number, dz: number)
.partial_tick() number

## Screen
.add_button(name: string, text: string, x: number, y: number, width: number, height: number) Widget
.add_checkbox(name: string, text: string, checked: bool, x: number, y: number, width: number, height: number) Widget
.add_list(name: string, x: number, y: number, width: number, height: number, row_height: number) Widget
.add_slider(name: string, text: string, min: number, max: number, value: number, x: number, y: number, width: number, height: number) Widget
.add_text_field(name: string, text: string, x: number, y: number, width: number, height: number) Widget
.close()
.open()
Screen.new() Screen

## ScreenInitContext
.screen_height() number
.screen_width() number

## ScreenWidgetContext
.widget() Widget

## Screenshot
.capture(x1: number, y1: number, x2: number, y2: number) Screenshot
.change_from(other: Screenshot) number
.differs_from(other: Screenshot, percent: number)
.equals(reference: resource, x1: number, y1: number, x2: number, y2: number)
.tolerance(percent: number) Screenshot

## ShapeContext
.add_collision_box(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)
.add_selection_box(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)

## SpawnSquares
.block_light(index: number) number
.count() number
.sky_light(index: number) number
.x(index: number) number
.y(index: number) number
.z(index: number) number

## String
String.concat(first: string, second: string) string
String.equals_ignore_case(text: string, other: string) bool
String.from_character(code: number) string
String.from_double(value: number) string
String.from_float(value: number) string
String.from_number(value: number) string
String.index_of(text: string, needle: string) number
String.length(text: string) number
String.substring(text: string, start: number, end: number) string
String.to_float(text: string) Option[number]
String.to_integer(text: string) Option[number]
String.to_number(text: string) Option[number]
String.trim(text: string) string

## TestInitContext
.get_origin() Vec3
.set_max_ticks(ticks: number)
.stall(milliseconds: number)

## TestTickContext
.assert(condition: bool, message: string)
.block_collision(level: Level, x: number, y: number, z: number, x1: number, y1: number, z1: number, x2: number, y2: number, z2: number) bool
.block_ray_trace(level: Level, x: number, y: number, z: number, start_x: number, start_y: number, start_z: number, end_x: number, end_y: number, end_z: number) bool
.block_selection(level: Level, x: number, y: number, z: number, point_x: number, point_y: number, point_z: number) bool
.break_block(level: Level, x: number, y: number, z: number)
.camera() Vec3
.clear_carried_item()
.clear_held_item(player: Player)
.click_screen(x: number, y: number, button: number) bool
.client_saved_number(block_entity: BlockEntity, name: string) number
.close_screen()
.current_tick() number
.display_tick(level: Level, x: number, y: number, z: number, seed: number) number
.expect_error(message: string)
.force_gc()
.get_carried_item() Option[Item]
.get_client_level() Level
.get_config() Config
.get_day_time(level: Level) number
.get_held_count(player: Player) number
.get_origin() Vec3
.get_player() Player
.get_spawn_squares(radius: number) SpawnSquares
.gui_height() number
.gui_left() number
.gui_top() number
.gui_width() number
.high_block_round_trip() number
.hot_reload_count(relative_path: string) number
.light_applied(x: number, y: number, z: number) bool
.list_row_error() string
.load_nbt(block_entity: BlockEntity)
.mining_crack(level: Level, x: number, y: number, z: number) bool
.normalization_forced() bool
.not_done()
.notify_neighbors(level: Level, x: number, y: number, z: number)
.open_inventory(player: Player)
.overlay_drawn() bool
.overlay_first_frame_cleared() bool
.particle_position(index: number) Vec3
.press_game_key(key: number)
.press_screen_key(key: number, ctrl: bool, shift: bool, release: bool) bool
.press_world_key(key: number, ctrl: bool)
.press_world_key_id(id: string)
.reload_count(relative_path: string) number
.remove_entities(level: Level, kind: string)
.round_trip_nbt(block_entity: BlockEntity)
.screen_open() bool
.screen_width() number
.screenshot() Screenshot
.scroll_screen(x: number, y: number, delta: number) bool
.set_box_radius(radius: number)
.set_carried_item(item: Item)
.set_daylight_cycle(level: Level, on: bool) bool
.set_gui_scale(scale: number)
.set_held_item(player: Player, item: Item)
.set_mod_file_state(relative_path: string, state: string)
.set_mouse_position(x: number, y: number)
.set_nbt_item(block_entity: BlockEntity, path: string, item: Item)
.set_nbt_number(block_entity: BlockEntity, path: string, type: string, value: number)
.set_player_position(x: number, y: number, z: number)
.spawn_mob(level: Level, id: string, x: number, y: number, z: number)
.stall(milliseconds: number)
.tick_block_entity(block_entity: BlockEntity, ticks: number)
.tick_level(level: Level, ticks: number) number
.use_block(level: Level, x: number, y: number, z: number)
.world_overlay_drawn() bool
.world_settled() bool

## Vec3
.x() number
.y() number
.z() number
Vec3.new(x: number, y: number, z: number) Vec3
Vec3.zero() Vec3

## Widget
.checked() bool
.select(index: number)
.selected() number
.set_active(active: bool)
.set_checked(checked: bool)
.set_position(x: number, y: number)
.set_rows(count: number)
.set_text(text: string)
.set_text_colour(color: Color)
.set_value(value: number)
.set_visible(visible: bool)
.text() string
.value() number

# Host functions

drop_inventory(level: Level, x: number, y: number, z: number)
equals(a: $Type, b: $Type) bool
give_item(item: Item)
give_item_stack(item: Item)
gui(texture_path: resource) GUI
has_sound(sound: string) bool
is_operator() bool
item(resource_location: ResourceLocation) Item
item_at(index: number) Item
item_count() number
item_entity(level: Level, x: number, y: number, z: number, item_stack: ItemStack) ItemEntity
item_max_stack(item: Item) number
item_stack(item: Item, damage: number, count: number) ItemStack
play_ui_sound(sound: string, volume: number, pitch: number)
print(value: $Value)
print2(a: $Value1, b: $Value2)
print3(a: $Value1, b: $Value2, c: $Value3)
resource_location(resource_location_string: string) ResourceLocation
search_item_at(text: string, index: number) Item
search_item_count(text: string) number
set_operator(operator: bool)
