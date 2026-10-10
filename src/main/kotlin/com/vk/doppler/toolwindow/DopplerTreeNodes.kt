package com.vk.doppler.toolwindow

import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerProject
import javax.swing.tree.DefaultMutableTreeNode

class ProjectNode(val dopplerProject: DopplerProject) : DefaultMutableTreeNode(dopplerProject) {
    /** Configs are fetched the first time the node is expanded. */
    var loaded: Boolean = false
}

class EnvironmentNode(val environment: String) : DefaultMutableTreeNode(environment)

class ConfigNode(
    val projectSlug: String,
    val config: DopplerConfig,
) : DefaultMutableTreeNode(config) {
    override fun isLeaf(): Boolean = true
}

class LoadingNode : DefaultMutableTreeNode("Loading…") {
    override fun isLeaf(): Boolean = true
}
