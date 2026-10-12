-- Dont39 All-in-One: mod-options-sidebar.
-- Options > Mods gets a right sidebar that lists every mod's section and scrolls the page to the one clicked. The page
-- is still built by vanilla: MainOptions:addModOptionsPanel is wrapped, not replaced. If the page is not what this
-- expects, or this code fails, the page stays as built, without the sidebar, and one line goes to console.txt.

require "OptionScreens/MainOptions"
require "ISUI/ISScrollingListBox"

local PREFIX = "[Dont39AllInOne] mod-options-sidebar: "
-- relative to ~/Zomboid/Lua/, the same place vanilla keeps keysB42.ini
local SAVE_FILE = "Dont39AllInOne_ModOptionsSidebar.txt"
local FONT = UIFont.Small
local GAP = 10
local MIN_WIDTH = 120
local MAX_WIDTH_FRACTION = 0.25
local TEXT_X = 15 -- ISScrollingListBox:doDrawItem draws item text at x = 15
local SCROLLBAR_WIDTH = 17 -- ISScrollBar:instantiate
local TOP_TOLERANCE = 2 -- a header this close below the top edge counts as "at the top"

-- Never throw: they are also called outside pcall, around the vanilla call.
local function log(message)
    pcall(function() DebugType.Mod:println(PREFIX .. message) end)
end

local function warn(message)
    pcall(function() DebugType.Mod:warn(PREFIX .. message) end)
end

if not MainOptions or not MainOptions.addModOptionsPanel or not ISScrollingListBox then
    log("idle: the game's options screen (MainOptions:addModOptionsPanel) was not found")
    return
end

local vanilla = MainOptions.addModOptionsPanel
local disabled = false -- after an error in this file; final for the session
local lastBuilt = nil -- the MainOptions whose Mods page went through the wrapper
local lastReported = nil -- the MainOptions the "replaced" warning was logged for
local pending = nil -- changes to MainOptions that last only while vanilla builds the page
local ioWarned = false

local function ioWarn(message)
    if ioWarned then return end
    ioWarned = true
    warn(message)
end

-- Saved choice -------------------------------------------------------------------------------------------------------

local function readChoice()
    local reader = getFileReader(SAVE_FILE, false)
    if not reader then return nil end
    local ok, line = pcall(function() return reader:readLine() end)
    pcall(function() reader:close() end)
    if not ok then error(line, 0) end
    line = line and string.match(line, "^%s*(.-)%s*$")
    if line == nil or line == "" then return nil end
    return line
end

local function writeChoice(id)
    local writer = getFileWriter(SAVE_FILE, true, false)
    if not writer then error("the file could not be opened", 0) end
    local ok, err = pcall(function() writer:write(id) end)
    pcall(function() writer:close() end)
    if not ok then error(err, 0) end
end

-- Undo ---------------------------------------------------------------------------------------------------------------

-- Never throws. Leaves alone anything someone else replaced in the meantime.
local function restorePending()
    local p = pending
    if not p then return end
    pending = nil
    local target = p.target
    if target.addHorizontalLine == p.recorder then
        target.addHorizontalLine = p.savedLine
    end
    if target.width == p.narrowedWidth then
        target.width = p.savedWidth
    end
end

-- Never throws. The page keeps the narrower layout it was built with; only the sidebar and its hooks go away.
local function detach(st)
    if not st or st.detached then return end
    st.detached = true
    local panel = st.panel
    if panel and st.ourPanelPrerender and panel.prerender == st.ourPanelPrerender then
        panel.prerender = st.panelPrerender
    end
    if st.vscrollMoved then
        st.vscrollMoved = false
        pcall(function() st.vscroll:setX(st.vscroll:getX() + st.shrink) end)
    end
    -- hidden rather than removed: this can run inside the list's own prerender
    if st.list then
        pcall(function() st.list:setVisible(false) end)
    end
end

local function fail(st, what, err)
    restorePending()
    detach(st)
    if disabled then return end
    disabled = true
    warn("disabled after an error while " .. what .. ": " .. tostring(err))
end

-- Sidebar ------------------------------------------------------------------------------------------------------------

local function jump(st, k)
    st.panel:setYScroll(-st.offsets[k]) -- setYScroll clamps to the scroll range
    st.clicked = k
    st.clickedScroll = st.panel:getYScroll()
    st.list.selected = k
    st.list:ensureVisible(k)
end

local function onClick(st, k)
    if st.detached then return end
    local ok, err = pcall(jump, st, k)
    if not ok then
        fail(st, "scrolling to a mod's options", err)
        return
    end
    ok, err = pcall(writeChoice, st.sections[k].id)
    if not ok then
        ioWarn("could not save the chosen mod to Zomboid/Lua/" .. SAVE_FILE .. " (" .. tostring(err) .. ")")
    end
end

-- Runs while the Mods page is visible: shows the sidebar, and on the first showing opens the saved choice.
local function panelFrame(st)
    if not st.list:isVisible() then
        st.list:setVisible(true)
        st.list.vscroll:setVisible(true)
    end
    if st.restoreIndex then
        local k = st.restoreIndex
        st.restoreIndex = nil
        jump(st, k)
    end
end

-- Runs while the sidebar is visible: hides it when another tab is active, else highlights the section at the top.
local function listFrame(st)
    if st.tabs:getActiveView() ~= st.panel then
        st.list:setVisible(false)
        -- the game still renders the list's children this frame (UIElement.render checks visibility before
        -- prerender only); hiding the scroll bar too keeps it from showing for one frame over the other tab
        st.list.vscroll:setVisible(false)
        return false
    end
    local scroll = st.panel:getYScroll()
    local k
    if st.clicked and scroll == st.clickedScroll then
        -- keep the clicked entry even when the page cannot scroll its header all the way up (last sections)
        k = st.clicked
    else
        st.clicked = nil
        local top = TOP_TOLERANCE - scroll
        local offsets = st.offsets
        k = 1
        for i = 2, st.count do
            if offsets[i] > top then break end
            k = i
        end
    end
    if st.list.selected ~= k then
        st.list.selected = k
        st.list:ensureVisible(k)
    end
    return true
end

-- Page build ---------------------------------------------------------------------------------------------------------

local function record(build, mainOptions, y, text)
    local sections = build.sections
    sections[#sections + 1] = { y = mainOptions.addY + y, name = text }
end

-- Before vanilla builds the page: size the sidebar, narrow the page, and record each section header.
local function prepare(self)
    local textManager = getTextManager()
    local textWidth = 0
    for _, options in ipairs(PZAPI.ModOptions.Data) do
        textWidth = math.max(textWidth, textManager:MeasureStringX(FONT, getText(options.name)))
    end
    local width = self.width
    local listWidth = math.max(textWidth + TEXT_X * 2 + SCROLLBAR_WIDTH, MIN_WIDTH)
    listWidth = math.floor(math.min(listWidth, width * MAX_WIDTH_FRACTION))
    local build = { listWidth = listWidth, shrink = listWidth + GAP * 2, sections = {} }

    -- vanilla emits exactly one addHorizontalLine per PZAPI.ModOptions.Data entry, as its header
    -- (MainOptions.lua:2805-2806); no option type calls it
    local addHorizontalLine = self.addHorizontalLine
    local function recorder(mainOptions, y, text)
        if build.sections and not pcall(record, build, mainOptions, y, text) then
            build.sections = nil
        end
        return addHorizontalLine(mainOptions, y, text)
    end

    -- the game's rawget falls back to the metatable (KahluaTableImpl.rawget), so "no instance field" is told apart by
    -- comparing with the class method; restoring nil lets the instance fall back to the class method again
    local savedLine = nil
    if addHorizontalLine ~= MainOptions.addHorizontalLine then
        savedLine = addHorizontalLine
    end

    -- Width trick: the parts of the page that reach the right edge size themselves from the Lua field self.width
    -- (headers MainOptions.lua:304, separators :321, descriptions :104, text entries :121), while the page panel and
    -- the label column use the Java width through self:getWidth() (:249, :2801; ISUIElement.lua:259). Lowering only
    -- the Lua field while vanilla runs lays the page out for (width - sidebar) without moving the label column. The
    -- page panel is not resized afterwards: a Java resize would also shift its right-anchored children (keybind
    -- labels). Fixed-width controls (sliders, combo boxes) do not follow; attach() keeps the sidebar clear of them.
    pending = {
        target = self,
        recorder = recorder,
        savedLine = savedLine,
        savedWidth = width,
        narrowedWidth = width - build.shrink,
    }
    self.addHorizontalLine = recorder
    self.width = pending.narrowedWidth
    return build
end

-- After vanilla built the page: check it is what prepare() expected, then add the sidebar.
-- Returns why the sidebar was not added, or nil.
local function attach(self, build)
    local data = PZAPI.ModOptions.Data
    local sections = build.sections
    if not sections then
        error("recording the section headers failed", 0)
    end
    if #sections ~= #data then
        return "the Mods page has " .. #sections .. " section headers for " .. #data .. " mods with options"
    end
    for k, section in ipairs(sections) do
        if section.name ~= getText(data[k].name) then
            return "section header " .. k .. " is not the one of " .. tostring(data[k].modOptionsID)
        end
        section.id = data[k].modOptionsID
    end
    local tabs, panel = self.tabs, self.mainPanel
    if not tabs or not panel or tabs:getView(getText("UI_mainscreen_mods")) ~= panel or not panel.vscroll then
        return "the Mods page is not laid out as expected"
    end

    -- Sliders and combo boxes have fixed widths and can reach past the narrowed page at large UI font sizes. Narrow the
    -- sidebar (not below MIN_WIDTH) so they and the page's scroll bar stay left of it.
    local contentRight = 0
    for _, child in pairs(panel:getChildren()) do
        if child ~= panel.vscroll then
            contentRight = math.max(contentRight, child:getRight())
        end
    end
    local room = panel:getWidth() - contentRight - SCROLLBAR_WIDTH - GAP * 2
    local listWidth = math.floor(math.min(build.listWidth, math.max(room, MIN_WIDTH)))
    local shrink = listWidth + GAP * 2

    local offsets = {}
    for k, section in ipairs(sections) do
        offsets[k] = section.y - sections[1].y
    end

    local okRead, saved = pcall(readChoice)
    if not okRead then
        ioWarn("could not read Zomboid/Lua/" .. SAVE_FILE .. " (" .. tostring(saved) .. ")")
        saved = nil
    end
    local restoreIndex = nil
    if saved then
        for k, section in ipairs(sections) do
            if section.id == saved then
                restoreIndex = k
                break
            end
        end
    end

    local st = {
        tabs = tabs,
        panel = panel,
        vscroll = panel.vscroll,
        panelPrerender = panel.prerender,
        sections = sections,
        offsets = offsets,
        count = #sections,
        shrink = shrink,
        restoreIndex = restoreIndex,
    }
    build.st = st

    -- a child of the tab panel, beside the Mods page rather than in it, so it does not scroll with the page and hides
    -- with the tabs (confirm dialogs); added last, so it is drawn over the page and gets clicks and the mouse wheel
    -- before it (UIElement.onMouseDown / onMouseWheel try children from the last one)
    local list = ISScrollingListBox:new(panel:getX() + panel:getWidth() - listWidth - GAP, panel:getY() + GAP,
        listWidth, panel:getHeight() - GAP * 2)
    list.anchorLeft = false
    list.anchorRight = true
    list.anchorTop = true
    list.anchorBottom = true
    list.drawBorder = true
    list:initialise()
    list:instantiate()
    list:setFont(FONT, 4)
    local textManager = getTextManager()
    for k, section in ipairs(sections) do
        local tooltip = nil
        if textManager:MeasureStringX(FONT, section.name) + TEXT_X * 2 + SCROLLBAR_WIDTH > listWidth then
            tooltip = section.name
        end
        list:addItem(section.name, k, tooltip)
    end
    list:setOnMouseDownFunction(st, onClick)
    list.prerender = function(l)
        if st.detached then return end
        local ok, show = pcall(listFrame, st)
        if not ok then
            fail(st, "updating the sidebar", show)
            return
        end
        if show then
            ISScrollingListBox.prerender(l)
        end
    end
    st.list = list

    st.ourPanelPrerender = function(p)
        st.panelPrerender(p)
        if st.detached then return end
        local ok, err = pcall(panelFrame, st)
        if not ok then
            fail(st, "showing the sidebar", err)
        end
    end

    tabs:addChild(list)
    list:setVisible(false)
    -- the page's scroll bar moves left of the sidebar (ISScrollBar.lua:274 puts it at the right edge)
    st.vscroll:setX(st.vscroll:getX() - shrink)
    st.vscrollMoved = true
    -- vanilla sets this per page instance (MainOptions.lua addPage), so wrapping it touches only the Mods page
    panel.prerender = st.ourPanelPrerender

    log("active (" .. #sections .. " sections)")
    return nil
end

function MainOptions:addModOptionsPanel(...)
    restorePending()
    lastBuilt = self
    local build = nil
    if not disabled then
        local ok, result = pcall(prepare, self)
        if ok then
            build = result
        else
            fail(nil, "preparing the Mods page", result)
        end
    end

    -- outside pcall, so errors of vanilla (or of mods it calls into) surface exactly as without this mod; if it throws,
    -- the narrowed width and the recorder are undone by afterMainScreen() below
    vanilla(self, ...)
    restorePending()

    if build and not disabled then
        local ok, problem = pcall(attach, self, build)
        if not ok then
            fail(build.st, "adding the sidebar", problem)
        elseif problem then
            detach(build.st)
            warn("inactive: " .. problem .. "; it is shown without the sidebar")
        end
    end
end

-- MainScreen builds MainOptions on these events (vanilla registered its handlers first, so they ran already).
local function checkReplaced()
    local mainOptions = MainOptions.instance
    if not mainOptions or mainOptions == lastBuilt or mainOptions == lastReported then return end
    local tabs = mainOptions.tabs
    if not tabs or not tabs:getView(getText("UI_mainscreen_mods")) then return end
    lastReported = mainOptions
    warn("inactive: another mod replaced MainOptions:addModOptionsPanel; the Mods page is shown without the sidebar")
end

local function afterMainScreen()
    restorePending()
    pcall(checkReplaced)
end

Events.OnMainMenuEnter.Add(afterMainScreen)
Events.OnGameStart.Add(afterMainScreen)

log("ready")
