package com.dont39.pz.syncguard.protocol;

/** What the server tracks per connection: a container or an item in one. */
public sealed interface Key permits ContainerKey, ItemKey {
    ContainerKey container();
}
