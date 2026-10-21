-- Dont39 All-in-One: mod sync. What the join screen and the rejoin offer share: keys and their dialog.

require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISRichTextPanel"

local Shared = {}

Shared.KEY = "UI_Dont39AllInOne_ModSync_"
Shared.PAD = 10

local PAD = Shared.PAD

-- Rich text [body] over a row of [buttons], each { title, onClick, accept }, side by side; onClick gets the dialog.
-- [extra], if given, adds a child between them: extra(dialog, y, rowHeight) returns its bottom. Centred, and opened
-- by dialog:open().
local Dialog = ISPanel:derive("Dont39AllInOne_ModSyncDialog")
Shared.Dialog = Dialog

function Dialog:new(width, body, buttons, extra)
    local x = (getCore():getScreenWidth() - width) / 2
    local o = ISPanel:new(x, 0, width, 100)
    setmetatable(o, self)
    self.__index = self
    o.body = body
    o.buttons = buttons
    o.extra = extra
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.9 }
    o.borderColor = { r = 0.4, g = 0.4, b = 0.4, a = 1 }
    o.moveWithMouse = true
    return o
end

function Dialog:createChildren()
    ISPanel.createChildren(self)
    local buttonHeight = math.max(25, getTextManager():getFontHeight(UIFont.Small) + 6)

    local text = ISRichTextPanel:new(PAD, PAD, self.width - 2 * PAD, 10)
    text:initialise()
    text:instantiate()
    text:noBackground()
    text:setMargins(0, 0, 0, 0)
    self:addChild(text)
    text.text = self.body
    text:paginate()

    local bottom = text:getBottom()
    if self.extra then bottom = self.extra(self, bottom + PAD, buttonHeight) end

    local y = bottom + PAD * 2
    local n = #self.buttons
    local buttonWidth = (self.width - (n + 1) * PAD) / n
    for i, b in ipairs(self.buttons) do
        local button = ISButton:new(PAD + (i - 1) * (buttonWidth + PAD), y, buttonWidth, buttonHeight, b.title, self,
            Dialog.onButton)
        button.answer = b
        button:initialise()
        button:instantiate()
        if b.accept then button:enableAcceptColor() else button:enableCancelColor() end
        self:addChild(button)
    end

    self:setHeight(y + buttonHeight + PAD)
    self:setY((getCore():getScreenHeight() - self:getHeight()) / 2)
end

function Dialog:onButton(button)
    button.answer.onClick(self)
end

function Dialog:open()
    self:initialise()
    self:addToUIManager()
    self:setAlwaysOnTop(true)
end

return Shared
