-- activitylog Neovim plugin.
--
-- Reports the active file, project, filetype and Git branch to the local
-- activitylog agent via POST /v1/editor. Requests are sent asynchronously
-- with curl through vim.system(); failures are ignored silently. The agent
-- applies privacy rules, so raw values are sent as-is.

local M = {}

local uv = vim.uv or vim.loop

local HEARTBEAT_MS = 30000
local EDIT_THROTTLE_MS = 2000
local GIT_TTL_MS = 60000
local LEAVE_WAIT_MS = 300

local defaults = {
  agent_url = "http://127.0.0.1:5610",
  enabled = true,
}

M.config = vim.deepcopy(defaults)

local PID = vim.fn.getpid()
local INSTANCE = tostring(PID)

local state = {
  configured = false, -- setup() has been called (explicitly or automatically)
  initial_pending = false,
  focused = true, -- assume focused at startup
  last_edit = 0,
  last_open_buf = nil,
  last_file_buf = nil,
  timer = nil,
  augroup = nil,
  git = {}, -- root -> { branch, repository, expires, pending }
}

local has_curl, has_git

local function now_ms()
  return uv.hrtime() / 1e6
end

-- RFC 3339 timestamp in UTC with millisecond precision.
local function rfc3339_now()
  local sec, usec = uv.gettimeofday()
  if not sec then
    return nil
  end
  return os.date("!%Y-%m-%dT%H:%M:%S", sec) .. string.format(".%03dZ", math.floor((usec or 0) / 1000))
end

-- A buffer that holds a regular file (not a terminal, help, plugin UI or a
-- URL-like name such as oil:// or fugitive://).
local function is_file_buf(buf)
  if not buf or not vim.api.nvim_buf_is_valid(buf) then
    return false
  end
  if vim.bo[buf].buftype ~= "" then
    return false
  end
  local name = vim.api.nvim_buf_get_name(buf)
  if name == "" or name:match("^%a[%w+.-]*://") then
    return false
  end
  return true
end

-- Fetches branch and remote URL for a repository root asynchronously and
-- caches them. Returns the cached entry (possibly still empty).
local function git_info(root)
  if has_git == nil then
    has_git = vim.fn.executable("git") == 1
  end
  if not root or not has_git then
    return nil
  end
  local entry = state.git[root]
  if not entry then
    entry = { expires = 0, pending = false }
    state.git[root] = entry
  end
  if entry.pending or entry.expires > now_ms() then
    return entry
  end

  entry.pending = true
  local remaining = 2
  local function done()
    remaining = remaining - 1
    if remaining == 0 then
      entry.pending = false
      entry.expires = now_ms() + GIT_TTL_MS
    end
  end

  local function run(cmd, on_value)
    local ok = pcall(vim.system, cmd, { text = true }, function(res)
      local value = nil
      if res and res.code == 0 and res.stdout then
        value = vim.trim(res.stdout)
        if value == "" then
          value = nil
        end
      end
      on_value(value)
      done()
    end)
    if not ok then
      done()
    end
  end

  run({ "git", "-C", root, "rev-parse", "--abbrev-ref", "HEAD" }, function(v)
    -- Detached HEAD prints "HEAD"; report no branch in that case.
    entry.branch = (v ~= "HEAD") and v or nil
  end)
  run({ "git", "-C", root, "config", "--get", "remote.origin.url" }, function(v)
    entry.repository = v
  end)
  return entry
end

local function build_payload(event, buf, focused)
  if not is_file_buf(buf) then
    buf = vim.api.nvim_get_current_buf()
    if not is_file_buf(buf) then
      buf = is_file_buf(state.last_file_buf) and state.last_file_buf or nil
    end
  end

  local payload = {
    editor = "neovim",
    app_name = "Neovim",
    instance = INSTANCE,
    pid = PID,
    event = event,
    focused = focused,
    time = rfc3339_now(),
  }

  local root
  if buf then
    local ok, r = pcall(vim.fs.root, buf, ".git")
    root = ok and r or nil
  else
    local ok, r = pcall(vim.fs.root, uv.cwd() or ".", ".git")
    root = ok and r or nil
  end

  local cwd = uv.cwd()
  local project_path = root or cwd
  if project_path then
    payload.project_path = project_path
    payload.project = vim.fs.basename(project_path)
  end

  if buf then
    payload.file = vim.fn.fnamemodify(vim.api.nvim_buf_get_name(buf), ":p")
    local ft = vim.bo[buf].filetype
    if ft ~= "" then
      payload.language = ft
    end
  end

  local g = git_info(root)
  if g then
    payload.branch = g.branch
    payload.repository = g.repository
  end

  return payload
end

local function post(payload, wait_ms)
  if has_curl == nil then
    has_curl = vim.fn.executable("curl") == 1
  end
  if not has_curl then
    return
  end
  local ok, body = pcall(vim.json.encode, payload)
  if not ok then
    return
  end
  local url = M.config.agent_url:gsub("/+$", "") .. "/v1/editor"
  local cmd = {
    "curl", "-sS", "-o", "/dev/null", "--max-time", "1",
    "-H", "Content-Type: application/json",
    "-X", "POST", "--data-binary", "@-", url,
  }
  local started, obj = pcall(vim.system, cmd, { stdin = body, text = true }, function() end)
  if started and obj and wait_ms then
    pcall(obj.wait, obj, wait_ms)
  end
end

local function send(event, buf, opts)
  opts = opts or {}
  if not M.config.enabled then
    return
  end
  -- Skip headless instances (e.g. `nvim --headless +PluginSync +qa`).
  if #vim.api.nvim_list_uis() == 0 and not opts.force then
    return
  end
  local focused = state.focused
  if opts.focused ~= nil then
    focused = opts.focused
  end
  local ok, payload = pcall(build_payload, event, buf, focused)
  if ok then
    pcall(post, payload, opts.wait_ms)
  end
end

local function stop_heartbeat()
  if state.timer then
    pcall(function()
      state.timer:stop()
      state.timer:close()
    end)
    state.timer = nil
  end
end

local function start_heartbeat()
  if state.timer or not M.config.enabled then
    return
  end
  local timer = uv.new_timer()
  if not timer then
    return
  end
  state.timer = timer
  timer:start(HEARTBEAT_MS, HEARTBEAT_MS, vim.schedule_wrap(function()
    if state.focused then
      send("heartbeat")
    end
  end))
end

local function on_focus_gained()
  state.focused = true
  start_heartbeat()
  send("focus")
end

local function on_focus_lost()
  state.focused = false
  stop_heartbeat()
  send("blur", nil, { focused = false })
end

local function create_autocmds()
  local group = vim.api.nvim_create_augroup("activitylog", { clear = true })
  state.augroup = group
  local au = function(events, cb)
    vim.api.nvim_create_autocmd(events, { group = group, callback = cb })
  end

  au("FocusGained", on_focus_gained)
  au("FocusLost", on_focus_lost)

  au("BufEnter", function(args)
    if not is_file_buf(args.buf) then
      return
    end
    state.last_file_buf = args.buf
    if state.last_open_buf == args.buf then
      return
    end
    state.last_open_buf = args.buf
    send("open", args.buf)
  end)

  au({ "TextChanged", "TextChangedI" }, function(args)
    if not is_file_buf(args.buf) then
      return
    end
    local t = now_ms()
    if t - state.last_edit < EDIT_THROTTLE_MS then
      return
    end
    state.last_edit = t
    send("edit", args.buf)
  end)

  au("BufWritePost", function(args)
    if is_file_buf(args.buf) then
      send("save", args.buf)
    end
  end)

  au("VimLeavePre", function()
    stop_heartbeat()
    -- Wait briefly so the request is written before Neovim exits.
    send("blur", nil, { focused = false, wait_ms = LEAVE_WAIT_MS })
  end)
end

local function teardown()
  stop_heartbeat()
  if state.augroup then
    pcall(vim.api.nvim_del_augroup_by_id, state.augroup)
    state.augroup = nil
  end
end

--- Configure and start the plugin. Safe to call multiple times.
---@param opts? { agent_url?: string, enabled?: boolean }
function M.setup(opts)
  local was_running = state.augroup ~= nil
  local new_config = vim.tbl_deep_extend("force", vim.deepcopy(defaults), opts or {})
  if was_running and (not new_config.enabled or new_config.agent_url ~= M.config.agent_url) then
    -- Close the current interval (using the old config) before switching URL
    -- or disabling.
    send("blur", nil, { focused = false })
  end
  M.config = new_config
  state.configured = true
  teardown()
  if not M.config.enabled then
    return
  end

  create_autocmds()
  if state.focused then
    start_heartbeat()
  end

  local function initial()
    state.initial_pending = false
    if is_file_buf(vim.api.nvim_get_current_buf()) then
      state.last_file_buf = vim.api.nvim_get_current_buf()
      state.last_open_buf = state.last_file_buf
    end
    if state.focused then
      send("focus")
    end
  end
  if vim.v.vim_did_enter == 1 then
    if not state.initial_pending then
      state.initial_pending = true
      vim.schedule(initial)
    end
  else
    -- UIEnter (not VimEnter) so that headless instances never report.
    vim.api.nvim_create_autocmd("UIEnter", { group = state.augroup, once = true, callback = initial })
  end
end

--- Called from plugin/activitylog.lua: set up with defaults unless the user
--- already called setup().
function M._auto_setup()
  if not state.configured then
    M.setup()
  end
end

return M
