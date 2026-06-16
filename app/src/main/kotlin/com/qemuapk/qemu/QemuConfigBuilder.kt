package com.qemuapk.qemu

/**
 * Fluent builder for constructing QEMU system-arm command line arguments.
 */
class QemuConfigBuilder {

    private val args = mutableListOf<String>()

    fun machine(type: String): QemuConfigBuilder {
        args.addAll(listOf("-M", type))
        return this
    }

    fun cpu(model: String): QemuConfigBuilder {
        args.addAll(listOf("-cpu", model))
        return this
    }

    fun smp(cores: Int): QemuConfigBuilder {
        args.addAll(listOf("-smp", cores.toString()))
        return this
    }

    fun memory(mb: Int): QemuConfigBuilder {
        args.addAll(listOf("-m", mb.toString()))
        return this
    }

    fun kernel(path: String): QemuConfigBuilder {
        args.addAll(listOf("-kernel", path))
        return this
    }

    fun initrd(path: String): QemuConfigBuilder {
        args.addAll(listOf("-initrd", path))
        return this
    }

    fun append(bootArgs: String): QemuConfigBuilder {
        args.addAll(listOf("-append", bootArgs))
        return this
    }

    /**
     * Add a drive image.
     * @param path Path to disk image file
     * @param format Image format (raw, qcow2, etc.)
     * @param ifType Interface type (virtio, ide, scsi)
     * @param readonly Whether the drive is read-only
     */
    fun drive(
        path: String,
        format: String = "raw",
        ifType: String = "virtio",
        readonly: Boolean = false
    ): QemuConfigBuilder {
        val driveSpec = buildString {
            append("file=$path,format=$format,if=$ifType")
            if (readonly) append(",readonly=on")
        }
        args.addAll(listOf("-drive", driveSpec))
        return this
    }

    fun device(name: String, vararg params: Pair<String, Any>): QemuConfigBuilder {
        val deviceSpec = if (params.isEmpty()) {
            name
        } else {
            "$name,${params.joinToString(",") { "${it.first}=${it.second}" }}"
        }
        args.addAll(listOf("-device", deviceSpec))
        return this
    }

    /**
     * Configure VNC display.
     * @param address VNC listen address, e.g., "127.0.0.1:0" for port 5900
     */
    fun displayVnc(address: String = "127.0.0.1:0"): QemuConfigBuilder {
        args.addAll(listOf("-display", "vnc=$address"))
        return this
    }

    /**
     * Configure user-mode networking with optional port forwarding.
     * @param hostForward Port forwarding rules, e.g., "tcp::5555-:5555"
     */
    fun networkUser(
        id: String = "net0",
        hostForward: String? = null
    ): QemuConfigBuilder {
        val netdevSpec = buildString {
            append("user,id=$id")
            if (hostForward != null) append(",hostfwd=$hostForward")
        }
        args.addAll(listOf("-netdev", netdevSpec))
        args.addAll(listOf("-device", "virtio-net-pci,netdev=$id"))
        return this
    }

    /**
     * Configure 9p shared folder between host and guest.
     */
    fun sharedFolder(
        hostPath: String,
        mountTag: String = "shared",
        securityModel: String = "none"
    ): QemuConfigBuilder {
        args.addAll(listOf(
            "-virtfs",
            "local,path=$hostPath,mount_tag=$mountTag,security_model=$securityModel"
        ))
        return this
    }

    fun serial(config: String = "mon:stdio"): QemuConfigBuilder {
        args.addAll(listOf("-serial", config))
        return this
    }

    fun noReboot(): QemuConfigBuilder {
        args.add("-no-reboot")
        return this
    }

    fun accel(type: String): QemuConfigBuilder {
        args.addAll(listOf("-accel", type))
        return this
    }

    /**
     * Add a raw argument to the QEMU command line.
     */
    fun rawArg(arg: String): QemuConfigBuilder {
        args.add(arg)
        return this
    }

    /**
     * Add a key-value argument.
     */
    fun arg(key: String, value: String): QemuConfigBuilder {
        args.addAll(listOf(key, value))
        return this
    }

    fun build(): List<String> = args.toList()

    fun buildString(): String = args.joinToString(" ") { arg ->
        if (arg.contains(" ") || arg.contains("=")) "\"$arg\"" else arg
    }
}
