            }


            // 🌲 工作流主动唤醒
            item {
            CardGroup(title = { Text("🌲 工作流主动唤醒") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用主动唤醒工具") },
                    supportingContent = { Text("开启后注册 trigger_proactive_message 工具。工作流可通过此工具唤醒 AI 在聊天中查岗或提醒你。用于督学、打卡等场景") },
                    trailingContent = {
                        Switch(
                            checked = systemToolsSetting.proactiveTriggerEnabled,
                            onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(proactiveTriggerEnabled = enabled)) }
                        )
                    }
                )
            }
            }


            // 📝 桌面便签
            item {
            CardGroup(title = { Text("📝 桌面便签") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用桌面便签工具") },
                    supportingContent = { Text("开启后注册 post_desk_note 工具。AI 可在你的手机桌面 Widget 上写/删提醒，解锁即见。先在桌面添加「橘瓣·桌面便签」小部件后使用。支持设置过期时间（默认24小时）") },
                    trailingContent = {
                        Switch(
                            checked = systemToolsSetting.deskNoteEnabled,
                            onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(deskNoteEnabled = enabled)) }
                        )
                    }
                )
            }
            }


        }

        PermissionManager(permissionState = locationPermissionState)