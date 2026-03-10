#!/usr/bin/env python3
"""
create-module.py — Tạo nhanh core hoặc feature module
Đặt trong bất kỳ thư mục nào (vd: tools/), chạy lần đầu sẽ hỏi config.

Usage:
    python create-module.py
    python create-module.py feature cart
    python create-module.py core analytics
"""

import os, sys, re, json

RESET  = "\033[0m";  BOLD  = "\033[1m"
CYAN   = "\033[96m"; GREEN = "\033[92m"
YELLOW = "\033[93m"; RED   = "\033[91m"
GRAY   = "\033[90m"

def bold(s):   return f"{BOLD}{s}{RESET}"
def cyan(s):   return f"{CYAN}{s}{RESET}"
def green(s):  return f"{GREEN}{s}{RESET}"
def yellow(s): return f"{YELLOW}{s}{RESET}"
def red(s):    return f"{RED}{s}{RESET}"
def gray(s):   return f"{GRAY}{s}{RESET}"

def clear(): os.system("cls" if os.name == "nt" else "clear")

def banner(pkg, root):
    print(f"{CYAN}{BOLD}")
    print("  ╔══════════════════════════════════════════╗")
    print("  ║       🤖  base_mvvm Module Creator       ║")
    print("  ╚══════════════════════════════════════════╝")
    print(RESET)
    print(f"  {gray('Package:')} {cyan(pkg)}")
    print(f"  {gray('Root   :')} {cyan(root)}")
    print()

def show_menu(title, options):
    print(f"  {bold(title)}")
    print(f"  {gray('─' * 44)}")
    for key, label in options:
        print(f"  {cyan(bold('[' + key + ']'))}  {label}")
    print(f"  {gray('─' * 44)}")

def prompt(msg, default=None):
    hint = f" {gray('(' + str(default) + ')')}" if default is not None else ""
    try:
        val = input(f"  {YELLOW}▶ {msg}{hint}: {RESET}").strip()
        return val if val else (str(default) if default is not None else "")
    except (KeyboardInterrupt, EOFError):
        print(f"\n\n  {gray('Tạm biệt! 👋')}\n")
        sys.exit(0)

def confirm(msg):
    return prompt(msg + f" {gray('(y/n)')}").lower() in ("y", "yes", "co", "có")

# ── Config ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR  = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(SCRIPT_DIR, ".module-config.json")

def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return None

def save_config(cfg):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)

def setup_config(force=False):
    existing = load_config()
    if existing and not force:
        return existing

    clear()
    print(f"{CYAN}{BOLD}")
    print("  ╔══════════════════════════════════════════╗")
    print("  ║         ⚙️   Cấu hình lần đầu            ║")
    print("  ╚══════════════════════════════════════════╝")
    print(RESET)

    # Đoán project root: thử ../  so với script (tools/ → project root)
    up_one = os.path.normpath(os.path.join(SCRIPT_DIR, ".."))
    if os.path.exists(os.path.join(up_one, "settings.gradle.kts")):
        guessed_root = up_one
    elif os.path.exists(os.path.join(os.getcwd(), "settings.gradle.kts")):
        guessed_root = os.getcwd()
    else:
        guessed_root = up_one  # fallback

    print(f"  {bold('📁 Project root')} — thư mục chứa settings.gradle.kts")
    root_input = prompt("Project root", default=guessed_root)
    root = os.path.normpath(os.path.expanduser(root_input))

    settings_path = os.path.join(root, "settings.gradle.kts")
    if not os.path.exists(settings_path):
        print(f"\n  {red('⚠️  Không tìm thấy settings.gradle.kts trong:')} {root}")
        print(f"  {yellow('Tiếp tục nhưng sẽ không update settings tự động.')}\n")

    # Đoán package từ settings
    guessed_pkg = "com.xxx.app"
    if os.path.exists(settings_path):
        with open(settings_path, encoding="utf-8") as f:
            content = f.read()
        m = re.search(r'rootProject\.name\s*=\s*"([^"]+)"', content)
        if m:
            guessed_pkg = f"com.xxx.{m.group(1)}"

    print()
    print(f"  {bold('📦 Base package')} — package gốc của project (vd: com.xxx.myapp)")
    pkg = prompt("Base package", default=guessed_pkg)

    cfg = {"base_package": pkg, "project_root": root}
    save_config(cfg)

    print(f"\n  {green('✅ Đã lưu config')} → {gray(os.path.relpath(CONFIG_FILE))}\n")
    return cfg

# ── File helpers ───────────────────────────────────────────────────────────────
def to_pascal(name):
    return "".join(w.capitalize() for w in name.replace("-", "_").split("_"))

def validate_name(name):
    name = name.lower().replace("-", "_")
    return name if re.match(r'^[a-z][a-z0-9_]*$', name) else None

def write_file(path, content, root):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    rel = os.path.relpath(path, root)
    print(f"    {green('+')} {rel}")

def add_to_settings(root, line):
    settings = os.path.join(root, "settings.gradle.kts")
    if not os.path.exists(settings):
        print(f"    {red('✗')} settings.gradle.kts không tìm thấy tại: {settings}")
        return
    with open(settings, "r", encoding="utf-8") as f:
        content = f.read()
    if line in content:
        print(f"    {yellow('~')} settings đã có: {line}")
        return
    with open(settings, "a", encoding="utf-8") as f:
        f.write(f"\n{line}")
    print(f"    {green('+')} settings.gradle.kts ← {line}")

# ── Create feature ─────────────────────────────────────────────────────────────
def create_feature(name, cfg):
    pkg_base = cfg["base_package"]
    root     = cfg["project_root"]

    pascal   = to_pascal(name)
    pkg      = f"{pkg_base}.feature.{name}"
    pkg_path = pkg.replace(".", "/")
    mod_dir  = os.path.join(root, "features", name)
    src      = os.path.join(mod_dir, "src", "main", "java", pkg_path)
    test_src = os.path.join(mod_dir, "src", "test", "java", pkg_path)

    if os.path.exists(mod_dir):
        print(f"\n  {red('Module features:' + name + ' đã tồn tại!')}\n")
        return

    print(f"\n  {bold('📦 Tạo files...')}\n")

    write_file(os.path.join(mod_dir, "build.gradle.kts"), f"""\
plugins {{
    id("base.android.feature")
}}

android {{
    namespace = "{pkg}"
}}

dependencies {{
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}}
""", root)

    write_file(os.path.join(src, "state", f"{pascal}State.kt"), f"""\
package {pkg}.state

data class {pascal}UiState(
    val isLoading: Boolean = false,
    val error: String? = null
)

sealed class {pascal}UiEvent {{
    object NavigateBack : {pascal}UiEvent()
    data class ShowSnackbar(val message: String) : {pascal}UiEvent()
}}
""", root)

    write_file(os.path.join(src, "viewmodel", f"{pascal}ViewModel.kt"), f"""\
package {pkg}.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import {pkg}.state.{pascal}UiEvent
import {pkg}.state.{pascal}UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class {pascal}ViewModel @Inject constructor() : ViewModel() {{

    private val _uiState = MutableStateFlow({pascal}UiState())
    val uiState: StateFlow<{pascal}UiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<{pascal}UiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    fun onBack() {{
        viewModelScope.launch {{ _uiEvent.send({pascal}UiEvent.NavigateBack) }}
    }}
}}
""", root)

    write_file(os.path.join(src, "screen", f"{pascal}Screen.kt"), f"""\
package {pkg}.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import {pkg_base}.core.ui.component.AppTopBar
import {pkg}.state.{pascal}UiEvent
import {pkg}.viewmodel.{pascal}ViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun {pascal}Screen(
    onNavigateBack: () -> Unit,
    viewModel: {pascal}ViewModel = hiltViewModel()
) {{
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember {{ SnackbarHostState() }}

    LaunchedEffect(Unit) {{
        viewModel.uiEvent.collectLatest {{ event ->
            when (event) {{
                is {pascal}UiEvent.NavigateBack -> onNavigateBack()
                is {pascal}UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            }}
        }}
    }}

    Scaffold(
        topBar       = {{ AppTopBar(title = "{pascal}", onNavigateUp = viewModel::onBack) }},
        snackbarHost = {{ SnackbarHost(snackbarHostState) }}
    ) {{ padding ->
        Box(
            modifier         = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {{
            Text("{pascal} Screen")
        }}
    }}
}}
""", root)

    route_const = name.upper() + "_ROUTE"
    write_file(os.path.join(src, "navigation", f"{pascal}Navigation.kt"), f"""\
package {pkg}.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import {pkg}.screen.{pascal}Screen

const val {route_const} = "{name}"

fun NavController.navigateTo{pascal}() = navigate({route_const})

fun NavGraphBuilder.{name}Screen(
    onNavigateBack: () -> Unit
) {{
    composable(route = {route_const}) {{
        {pascal}Screen(onNavigateBack = onNavigateBack)
    }}
}}
""", root)

    write_file(os.path.join(mod_dir, "src", "main", "res", "values", "strings.xml"), f"""\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="feature_{name}_title">{pascal}</string>
</resources>
""", root)

    write_file(os.path.join(test_src, "viewmodel", f"{pascal}ViewModelTest.kt"), f"""\
package {pkg}.viewmodel

import org.junit.Test

class {pascal}ViewModelTest {{

    @Test
    fun `initial state is correct`() {{
        // TODO: add ViewModel tests
    }}
}}
""", root)

    add_to_settings(root, f'include(":features:{name}")')

    impl = f'implementation(project(":features:{name}"))'
    imp1 = f'import {pkg}.navigation.{name}Screen'
    imp2 = f'import {pkg}.navigation.navigateTo{pascal}'
    nav  = f'{name}Screen(onNavigateBack = {{ navController.popBackStack() }})'
    print()
    print(f"  {green('✅ Feature')} {bold(name)} {green('tạo xong!')}")
    print()
    print(f"  {bold('📋 Bước tiếp theo:')}")
    print()
    print(f"  1. {cyan('app/build.gradle.kts')}:")
    print(f"     {gray(impl)}")
    print()
    print(f"  2. {cyan('AppNavHost.kt')}:")
    print(f"     {gray(imp1)}")
    print(f"     {gray(imp2)}")
    print(f"     {gray(nav)}")
    print()

# ── Create core ────────────────────────────────────────────────────────────────
def create_core(name, cfg):
    pkg_base = cfg["base_package"]
    root     = cfg["project_root"]

    pascal   = to_pascal(name)
    pkg      = f"{pkg_base}.core.{name}"
    pkg_path = pkg.replace(".", "/")
    mod_dir  = os.path.join(root, "core", name)
    src      = os.path.join(mod_dir, "src", "main", "java", pkg_path)
    test_src = os.path.join(mod_dir, "src", "test", "java", pkg_path)

    if os.path.exists(mod_dir):
        print(f"\n  {red('Module core:' + name + ' đã tồn tại!')}\n")
        return

    print(f"\n  {bold('📦 Tạo files...')}\n")

    write_file(os.path.join(mod_dir, "build.gradle.kts"), f"""\
plugins {{
    id("base.android.library")
    id("base.android.hilt")
}}

android {{
    namespace = "{pkg}"
}}

dependencies {{
    implementation(project(":core:common"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}}
""", root)

    write_file(os.path.join(src, "di", f"{pascal}Module.kt"), f"""\
package {pkg}.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object {pascal}Module {{
    // TODO: thêm @Provides / @Binds ở đây
}}
""", root)

    write_file(os.path.join(test_src, f"{pascal}Test.kt"), f"""\
package {pkg}

import org.junit.Test

class {pascal}Test {{

    @Test
    fun `placeholder test`() {{
        // TODO: add tests
    }}
}}
""", root)

    add_to_settings(root, f'include(":core:{name}")')

    impl = f'implementation(project(":core:{name}"))'
    print()
    print(f"  {green('✅ Core module')} {bold(name)} {green('tạo xong!')}")
    print()
    print(f"  {bold('📋 Bước tiếp theo:')}")
    print()
    print(f"  Thêm vào module cần dùng ({cyan('build.gradle.kts')}):")
    print(f"  {gray(impl)}")
    print()

# ── Interactive menu ───────────────────────────────────────────────────────────
def run_menu(cfg):
    clear()
    banner(cfg["base_package"], cfg["project_root"])

    while True:
        show_menu("Chọn loại module:", [
            ("1", "feature   — Screen + ViewModel + State + Navigation"),
            ("2", "core      — Library + Hilt Module"),
            ("c", "config    — Đổi package / root dir"),
            ("0", "exit      — Thoát"),
        ])
        print()
        choice = prompt("Nhập lựa chọn")

        if choice in ("0", "q", "exit"):
            print(f"\n  {gray('Tạm biệt! 👋')}\n")
            sys.exit(0)

        if choice == "c":
            cfg = setup_config(force=True)
            clear()
            banner(cfg["base_package"], cfg["project_root"])
            continue

        if choice not in ("1", "2"):
            print(f"  {red('Lựa chọn không hợp lệ.')}\n")
            continue

        module_type = "feature" if choice == "1" else "core"
        example     = "cart" if module_type == "feature" else "analytics"

        print()
        while True:
            raw = prompt(f"Tên module {cyan(module_type)} (vd: {example})")
            if not raw:
                print(f"  {red('Tên không được để trống.')}")
                continue
            name = validate_name(raw)
            if not name:
                print(f"  {red('Tên không hợp lệ — chỉ dùng chữ thường, số, dấu _')}")
                continue
            break

        pascal     = to_pascal(name)
        prefix     = "features" if module_type == "feature" else "core"
        pkg_type   = "feature" if module_type == "feature" else "core"
        pkg_prev   = f"{cfg['base_package']}.{pkg_type}.{name}"

        print()
        print(f"  {bold('Preview:')}")
        print(f"  {gray('Module  :')} :{prefix}:{name}")
        print(f"  {gray('Package :')} {pkg_prev}")
        if module_type == "feature":
            print(f"  {gray('Classes :')} {pascal}UiState, {pascal}ViewModel, {pascal}Screen, {pascal}Navigation")
        else:
            print(f"  {gray('Classes :')} {pascal}Module")
        print()

        # if not confirm("Xác nhận tạo?"):
        #     print(f"\n  {yellow('Đã huỷ.')}\n")
        # else:
        if module_type == "feature":
            create_feature(name, cfg)
        else:
            create_core(name, cfg)

        # if not confirm("Tạo module khác?"):
        #     print(f"\n  {gray('Tạm biệt! 👋')}\n")
        #     break

        input("\nEnter to continue...")
        clear()
        banner(cfg["base_package"], cfg["project_root"])

# ── Entry point ────────────────────────────────────────────────────────────────
def main():
    cfg = load_config()
    if not cfg:
        cfg = setup_config()

    if len(sys.argv) == 3:
        module_type = sys.argv[1].lower()
        name = validate_name(sys.argv[2])
        if not name:
            print(red(f"Tên không hợp lệ: '{sys.argv[2]}'"))
            sys.exit(1)
        if module_type == "feature":
            create_feature(name, cfg)
        elif module_type == "core":
            create_core(name, cfg)
        else:
            print(red(f"Loại không hợp lệ: '{module_type}'"))
            sys.exit(1)
        return

    run_menu(cfg)

if __name__ == "__main__":
    main()
