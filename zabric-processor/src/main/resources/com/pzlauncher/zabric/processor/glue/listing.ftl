<#-- Read by Zabric's HookListing: a change its readers must understand takes a new header version. -->
${header}
mod ${unit.modId}
gate ${listing.gate}
<#list listing.patches as p>
patch ${unit.binaryName(p)}
</#list>
<#list listing.patches as p>
hook ${p.target.owner} ${p.target.method} ${p.target.descriptor} ${unit.binaryName(p)}
</#list>
