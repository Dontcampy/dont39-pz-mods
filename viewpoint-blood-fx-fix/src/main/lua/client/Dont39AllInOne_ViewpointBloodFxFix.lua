if not PZAPI or not PZAPI.ModOptions then return end

local options = PZAPI.ModOptions:create("Dont39AllInOne_ViewpointBloodFxFix", "Dont39: Blood FX for Viewpoint fix")
options:addTickBox("force", "Apply to untested Blood FX versions", false,
    "The fix only runs on the Blood FX versions it was tested with, since a newer Blood FX may handle terrain relief itself. Tick to apply it anyway.")

function options:apply()
    if Dont39AllInOne_setViewpointBloodFxFixForce then
        Dont39AllInOne_setViewpointBloodFxFixForce(self:getOption("force"):getValue())
    end
end

Events.OnGameStart.Add(function() options:apply() end)
Events.OnMainMenuEnter.Add(function() options:apply() end)
