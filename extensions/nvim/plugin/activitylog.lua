-- Auto-start activitylog with default options. Set `vim.g.activitylog_disable = true`
-- before this file is sourced to opt out, or call require("activitylog").setup({...})
-- yourself (before or after this file runs) to override the defaults.
if vim.g.loaded_activitylog then
  return
end
vim.g.loaded_activitylog = true

if vim.g.activitylog_disable then
  return
end

if vim.fn.has("nvim-0.10") ~= 1 then
  return
end

require("activitylog")._auto_setup()
