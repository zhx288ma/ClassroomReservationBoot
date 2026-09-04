const state = {
    token: localStorage.getItem("crs.token") || "",
    user: JSON.parse(localStorage.getItem("crs.user") || "null"),
    rooms: [],
    roomSlots: [],
    credit: null,
    submitToken: "",
    logs: [],
    eventSource: null
};

const API_BASE = (() => {
    const localBackend = "http://localhost:8081";
    if (location.protocol === "file:") {
        return localBackend;
    }
    const isLocalhost = location.hostname === "localhost" || location.hostname === "127.0.0.1";
    if (isLocalhost && location.port && location.port !== "8081") {
        return localBackend;
    }
    return "";
})();

const routes = {
    login: { title: "账号登录", subtitle: "登录后进入对应角色工作台", public: true },
    register: { title: "学生注册", subtitle: "创建普通学生账号", public: true },
    dashboard: { title: "控制台", subtitle: "查看当前账号可访问的预约工作台", roles: ["ADMIN", "USER"] },
    reserve: { title: "预约教室", subtitle: "选择教室、日期和时间段后提交预约", roles: ["USER"] },
    advisor: { title: "智能推荐", subtitle: "按人数、楼栋和时间段匹配可用教室", roles: ["USER"] },
    agent: { title: "智能预约与校规助手", subtitle: "通过受控工具检索时段、制度知识并生成待确认预约草稿", roles: ["ADMIN", "USER"] },
    orders: { title: "预约列表", subtitle: "学生查看本人预约，管理员查看全量预约", roles: ["ADMIN", "USER"] },
    rooms: { title: "教室管理", subtitle: "管理员维护教室资源", roles: ["ADMIN"] },
    roomSlots: { title: "时段管理", subtitle: "管理员维护 room_slot 开放、关闭和维护状态", roles: ["ADMIN"] },
    statistics: { title: "统计大屏", subtitle: "Kafka 事件流消费后的统计结果与 outbox 状态", roles: ["ADMIN"] },
    ops: { title: "中间件运维", subtitle: "查看 Redis、RabbitMQ 和缓存状态", roles: ["ADMIN"] },
    feedback: { title: "问题反馈", subtitle: "学生提交问题，管理员回复处理", roles: ["ADMIN", "USER"] },
    notifications: { title: "通知中心", subtitle: "查看 RabbitMQ 消费后的站内通知", roles: ["ADMIN", "USER"] },
    logs: { title: "联调日志", subtitle: "查看最近的页面操作和接口结果", roles: ["ADMIN"] }
};

const $ = (id) => document.getElementById(id);
const isAdmin = () => state.user && state.user.role === "ADMIN";
const isStudent = () => state.user && state.user.role === "USER";
const formatLocalDate = (date) => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
};
const today = () => formatLocalDate(new Date());
const tomorrow = () => {
    const date = new Date();
    date.setDate(date.getDate() + 1);
    return formatLocalDate(date);
};

function init() {
    $("apiBaseText").textContent = API_BASE || location.origin;
    initReservableControls();
    bindEvents();
    renderSession();
    renderRoute();
    if (state.token) {
        connectSse();
        refreshAll();
    }
}

function initReservableControls() {
    initFutureDateInput($("reserveDate"));
    initFutureDateInput($("advisorDate"));
    initFutureDateInput($("slotDate"));
    initFutureDateInput($("slotEndDate"));
    updateTimeSlotOptions("reserveDate", "timeSlot");
    updateTimeSlotOptions("advisorDate", "advisorTimeSlot");
    updateTimeSlotOptions("slotDate", "slotTimeSlot");
}

function initFutureDateInput(input) {
    input.min = today();
    if (!input.value || input.value < today()) {
        input.value = defaultReservableDate();
    }
}

function defaultReservableDate() {
    return hasAvailableSlotToday("timeSlot") || hasAvailableSlotToday("advisorTimeSlot") ? today() : tomorrow();
}

function hasAvailableSlotToday(selectId) {
    const select = $(selectId);
    return Boolean(select && Array.from(select.options).some((option) => isSlotStartAfterNow(option.value)));
}

function currentMinutes() {
    const now = new Date();
    return now.getHours() * 60 + now.getMinutes();
}

function slotStartMinutes(value) {
    const match = /^(\d{2}):(\d{2})-/.exec(value || "");
    return match ? Number(match[1]) * 60 + Number(match[2]) : -1;
}

function isSlotStartAfterNow(value) {
    return slotStartMinutes(value) > currentMinutes();
}

function formatDateTimeMinute(date) {
    const hours = String(date.getHours()).padStart(2, "0");
    const minutes = String(date.getMinutes()).padStart(2, "0");
    return `${formatLocalDate(date)} ${hours}:${minutes}`;
}

function signWindowInfo(order) {
    if (!order || !order.reserveDate || !order.timeSlot) {
        return { canSign: false, message: "预约时间信息不完整" };
    }
    const startMinutes = slotStartMinutes(order.timeSlot);
    if (startMinutes < 0) {
        return { canSign: false, message: "预约时间段格式不正确" };
    }
    const hours = String(Math.floor(startMinutes / 60)).padStart(2, "0");
    const minutes = String(startMinutes % 60).padStart(2, "0");
    const start = new Date(`${order.reserveDate}T${hours}:${minutes}:00`);
    const windowStart = new Date(start.getTime() - 15 * 60 * 1000);
    const windowEnd = new Date(start.getTime() + 15 * 60 * 1000);
    const now = new Date();
    const range = `${formatDateTimeMinute(windowStart)} - ${formatDateTimeMinute(windowEnd)}`;
    if (now < windowStart) {
        return { canSign: false, message: `未到签到时间，可签到：${range}` };
    }
    if (now > windowEnd) {
        return { canSign: false, message: `签到窗口已过：${range}` };
    }
    return { canSign: true, message: `当前可签到，窗口：${range}` };
}

function updateTimeSlotOptions(dateInputId, selectId) {
    const input = $(dateInputId);
    const select = $(selectId);
    if (!input || !select) {
        return;
    }
    const sameDay = input.value === today();
    let firstEnabled = "";
    Array.from(select.options).forEach((option) => {
        option.disabled = sameDay && !isSlotStartAfterNow(option.value);
        if (!option.disabled && !firstEnabled) {
            firstEnabled = option.value;
        }
    });
    if (select.selectedOptions[0] && select.selectedOptions[0].disabled && firstEnabled) {
        select.value = firstEnabled;
    }
    if (sameDay && !firstEnabled) {
        input.value = tomorrow();
        updateTimeSlotOptions(dateInputId, selectId);
    }
}

function bindEvents() {
    $("loginForm").addEventListener("submit", login);
    $("registerForm").addEventListener("submit", register);
    $("reserveForm").addEventListener("submit", reserve);
    $("advisorForm").addEventListener("submit", recommend);
    $("agentForm").addEventListener("submit", askAgent);
    $("agentKnowledgeForm").addEventListener("submit", saveAgentKnowledge);
    $("agentKnowledgeUploadForm").addEventListener("submit", uploadAgentKnowledge);
    $("agentKnowledgeRefreshBtn").addEventListener("click", loadAgentKnowledge);
    $("agentKnowledgeRebuildBtn").addEventListener("click", rebuildAgentKnowledge);
    $("agentKnowledgeList").addEventListener("click", removeAgentKnowledge);
    $("agentTraceRefreshBtn").addEventListener("click", loadAgentTraces);
    document.querySelectorAll("[data-agent-prompt]").forEach((button) => {
        button.addEventListener("click", () => {
            $("agentMessage").value = button.dataset.agentPrompt || "";
            $("agentMessage").focus();
        });
    });
    $("agentCandidates").addEventListener("click", useAgentCandidate);
    $("roomForm").addEventListener("submit", saveRoom);
    $("slotForm").addEventListener("submit", saveRoomSlot);
    $("slotBatchBtn").addEventListener("click", batchCreateRoomSlots);
    $("slotReconcileBtn").addEventListener("click", reconcileRoomSlotCounters);
    $("slotRefreshBtn").addEventListener("click", loadRoomSlots);
    $("roomSlotsBody").addEventListener("click", handleRoomSlotAction);
    $("statisticsRefreshBtn").addEventListener("click", loadStatistics);
    $("feedbackForm").addEventListener("submit", submitFeedback);
    $("feedbackReplyForm").addEventListener("submit", replyFeedback);
    $("feedbackCloseBtn").addEventListener("click", closeSelectedFeedback);
    $("feedbackResetBtn").addEventListener("click", resetFeedbackReplyForm);
    $("roomResetBtn").addEventListener("click", resetRoomForm);
    $("submitTokenBtn").addEventListener("click", createSubmitToken);
    $("stockBtn").addEventListener("click", loadStock);
    $("syncStockBtn").addEventListener("click", syncStock);
    $("clearDemoBtn").addEventListener("click", clearDemoKeys);
    $("refreshAllBtn").addEventListener("click", refreshAll);
    $("reserveDate").addEventListener("change", () => updateTimeSlotOptions("reserveDate", "timeSlot"));
    $("advisorDate").addEventListener("change", () => updateTimeSlotOptions("advisorDate", "advisorTimeSlot"));
    $("slotDate").addEventListener("change", () => updateTimeSlotOptions("slotDate", "slotTimeSlot"));
    $("clearLogBtn").addEventListener("click", () => {
        state.logs = [];
        renderLogs();
    });
    $("logoutBtn").addEventListener("click", logout);
    const sidebarToggle = document.querySelector(".sidebar-toggle");
    if (sidebarToggle) {
        sidebarToggle.addEventListener("click", () => document.body.classList.toggle("sidebar-open"));
    }
    document.querySelectorAll("[data-route]").forEach((link) => {
        link.addEventListener("click", (event) => {
            event.preventDefault();
            navigateTo(link.dataset.route);
        });
    });
    window.addEventListener("hashchange", renderRoute);
}

function requestedRoute() {
    const route = location.hash.replace(/^#\/?/, "") || (state.token ? defaultRoute() : "login");
    return routes[route] ? route : (state.token ? defaultRoute() : "login");
}

function defaultRoute() {
    return "dashboard";
}

function currentRoute() {
    const route = requestedRoute();
    const config = routes[route];
    if (!state.token) {
        return config.public ? route : "login";
    }
    if (!state.user) {
        state.token = "";
        localStorage.removeItem("crs.token");
        localStorage.removeItem("crs.user");
        return "login";
    }
    if (config.public) {
        return defaultRoute();
    }
    return isRouteAllowed(route) ? route : defaultRoute();
}

function isRouteAllowed(route) {
    const config = routes[route];
    return Boolean(config && (!config.roles || (state.user && config.roles.includes(state.user.role))));
}

function navigateTo(route) {
    const target = routes[route] ? route : (state.token ? defaultRoute() : "login");
    if (currentRoute() === target && location.hash) {
        renderRoute();
        return;
    }
    location.hash = `/${target}`;
}

function renderRoute() {
    const route = currentRoute();
    if (route !== requestedRoute()) {
        history.replaceState(null, "", `${location.pathname}${location.search}#/${route}`);
    }
    document.querySelectorAll(".page-view").forEach((view) => {
        view.classList.toggle("active", view.dataset.page === route);
    });
    const authMode = Boolean(routes[route].public);
    document.body.classList.toggle("auth-mode", authMode);
    document.body.classList.toggle("role-admin", Boolean(isAdmin()));
    document.body.classList.toggle("role-user", Boolean(isStudent()));
    $("pageTitle").textContent = routes[route].title;
    $("pageSubtitle").textContent = routes[route].subtitle;
    renderMenus(route);
    renderRoleChrome();
    document.body.classList.remove("sidebar-open");
    window.scrollTo(0, 0);
}

function renderMenus(route) {
    document.querySelectorAll(".sidebar-menu li[data-roles]").forEach((item) => {
        const roles = item.dataset.roles.split(",");
        const visible = Boolean(state.user && roles.includes(state.user.role));
        item.hidden = !visible;
        item.classList.remove("active");
        const link = item.querySelector("[data-route]");
        if (visible && link && link.dataset.route === route) {
            item.classList.add("active");
        }
    });
}

function renderRoleChrome() {
    const roleName = isAdmin() ? "管理员端" : isStudent() ? "学生端" : "未登录";
    $("roleBadge").textContent = roleName;
    $("sideRoleText").textContent = roleName;
    $("sideUserName").textContent = state.user ? state.user.username : "未登录";
    $("ordersTitle").textContent = isAdmin() ? "预约总览" : "我的预约";
    $("ordersSubtitle").textContent = isAdmin() ? "管理员可查看全量预约与候补队列" : "学生可查看预约、签到、取消预约或退出候补";
    $("feedbackTitleText").textContent = isAdmin() ? "全量反馈" : "我的反馈";
    $("feedbackSubtitle").textContent = isAdmin() ? "管理员处理学生反馈并发送站内通知" : "反馈会进入管理员工作台，回复后会通过通知中心提醒";
}

async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    const isFormData = typeof FormData !== "undefined" && options.body instanceof FormData;
    if (!headers.has("Content-Type") && options.body && !isFormData) {
        headers.set("Content-Type", "application/json");
    }
    if (state.token) {
        headers.set("X-Token", state.token);
    }
    const response = await fetch(API_BASE + path, { ...options, headers });
    if (response.status === 401) {
        clearSession();
        throw new Error("登录状态已失效，请重新登录");
    }
    if (response.status === 403) {
        throw new Error("当前账号无权限访问该功能");
    }
    const payload = await response.json();
    if (!payload.success) {
        throw new Error(payload.message || "请求失败");
    }
    return payload.data;
}

function assertPhone(phone) {
    if (!/^1[3-9]\d{9}$/.test(phone)) {
        throw new Error("手机号格式不正确");
    }
}

function assertReservableDateTime(value, timeSlot) {
    assertTimeSlot(timeSlot);
    if (!value || value < today()) {
        throw new Error("预约日期不能早于今天");
    }
    if (value === today() && !isSlotStartAfterNow(timeSlot)) {
        throw new Error("当天预约必须选择当前时间之后的时间段");
    }
}

function assertTimeSlot(value) {
    const match = /^(\d{2}):(\d{2})-(\d{2}):(\d{2})$/.exec(value || "");
    if (!match) {
        throw new Error("时间段格式必须是 HH:mm-HH:mm");
    }
    const start = Number(match[1]) * 60 + Number(match[2]);
    const end = Number(match[3]) * 60 + Number(match[4]);
    if (start < 8 * 60 || end > 20 * 60 || start >= end) {
        throw new Error("预约时间段必须在 08:00 到 20:00 之间");
    }
}

async function login(event) {
    event.preventDefault();
    try {
        const phone = $("loginPhone").value.trim();
        assertPhone(phone);
        const data = await request("/auth/login", {
            method: "POST",
            body: JSON.stringify({
                phone,
                password: $("loginPassword").value
            })
        });
        state.token = data.token;
        state.user = {
            userId: data.userId,
            username: data.username,
            role: data.role
        };
        localStorage.setItem("crs.token", state.token);
        localStorage.setItem("crs.user", JSON.stringify(state.user));
        renderSession();
        connectSse();
        addLog("登录成功：" + data.username);
        navigateTo(defaultRoute());
        await refreshAll();
    } catch (error) {
        addLog(error.message, true);
    }
}

async function register(event) {
    event.preventDefault();
    try {
        const phone = $("registerPhone").value.trim();
        assertPhone(phone);
        await request("/auth/register", {
            method: "POST",
            body: JSON.stringify({
                username: $("registerUsername").value.trim(),
                phone,
                password: $("registerPassword").value
            })
        });
        $("loginPhone").value = phone;
        $("loginPassword").value = $("registerPassword").value;
        addLog("注册成功，请登录学生端");
        navigateTo("login");
    } catch (error) {
        addLog(error.message, true);
    }
}

async function logout() {
    try {
        if (state.token) {
            await request("/auth/logout", { method: "POST" });
        }
    } catch (error) {
        addLog(error.message, true);
    } finally {
        clearSession();
        addLog("已退出登录");
    }
}

function clearSession() {
    state.token = "";
    state.user = null;
    state.submitToken = "";
    localStorage.removeItem("crs.token");
    localStorage.removeItem("crs.user");
    disconnectSse();
    renderSession();
    $("submitTokenText").textContent = "未生成";
    navigateTo("login");
}

function renderSession() {
    $("sessionUser").textContent = state.user ? `${state.user.username} / ${state.user.role}` : "未登录";
    renderRoleChrome();
}

async function refreshAll() {
    if (!state.token) {
        renderRoute();
        return;
    }
    try {
        const tasks = [
            loadRooms(),
            loadOrders(),
            loadFeedbacks(),
            loadDashboard(),
            loadNotifications()
        ];
        if (isAdmin()) {
            tasks.push(loadRedisOverview(), loadMqOverview(), loadAuditLogs(), loadRoomSlots(), loadStatistics(), loadAgentKnowledge(), loadAgentTraces());
        }
        if (isStudent()) {
            tasks.push(loadCredit());
        }
        await Promise.all(tasks);
        addLog("数据刷新完成");
    } catch (error) {
        addLog(error.message, true);
    } finally {
        renderRoute();
    }
}

async function loadRooms() {
    const query = isAdmin() ? "/rooms?limit=100&includeDisabled=true" : "/rooms?limit=100";
    state.rooms = await request(query);
    renderRooms();
    renderRoomsTable();
}

function renderRooms() {
    const availableRooms = state.rooms.filter((room) => room.status === 1);
    const options = availableRooms.map((room) => {
        const name = `${room.buildingName || ""} ${room.roomNumber || ""} / ${room.capacity || 0}人`;
        return `<option value="${room.id}">${escapeHtml(name)}</option>`;
    });
    $("roomSelect").innerHTML = options.join("") || "<option value=\"1\">默认教室 1</option>";
    const slotSelect = $("slotRoomSelect");
    if (slotSelect) {
        slotSelect.innerHTML = state.rooms.map((room) => {
            const name = `${room.buildingName || ""} ${room.roomNumber || ""} / ${room.capacity || 0}人 / ${room.status === 1 ? "可用" : "停用"}`;
            return `<option value="${room.id}">${escapeHtml(name)}</option>`;
        }).join("") || "<option value=\"1\">默认教室 1</option>";
    }
}

function renderRoomsTable() {
    const target = $("roomsBody");
    if (!target) {
        return;
    }
    target.innerHTML = state.rooms.map((room) => `<tr>
        <td>${room.id}</td>
        <td>${escapeHtml(room.buildingName || "")}</td>
        <td>${escapeHtml(room.roomNumber || "")}</td>
        <td>${room.capacity || 0}</td>
        <td>${escapeHtml(room.roomType || "")}</td>
        <td>${room.status === 1 ? "可用" : "停用"}</td>
        <td class="text-center"><button class="small secondary" data-action="edit-room" data-room-id="${room.id}" type="button">编辑</button></td>
    </tr>`).join("") || `<tr><td colspan="7">暂无教室</td></tr>`;
    target.querySelectorAll("[data-action='edit-room']").forEach((button) => {
        button.addEventListener("click", () => editRoom(button.dataset.roomId));
    });
}

function editRoom(roomId) {
    const room = state.rooms.find((item) => String(item.id) === String(roomId));
    if (!room) {
        return;
    }
    $("roomId").value = room.id || "";
    $("roomBuildingName").value = room.buildingName || "";
    $("roomNumber").value = room.roomNumber || "";
    $("roomCapacity").value = room.capacity || 1;
    $("roomType").value = room.roomType || "SEMINAR";
    $("roomEquipment").value = room.equipment || "";
    $("roomStatus").value = String(room.status ?? 1);
    navigateTo("rooms");
}

function resetRoomForm() {
    $("roomId").value = "";
    $("roomBuildingName").value = "";
    $("roomNumber").value = "";
    $("roomCapacity").value = "";
    $("roomType").value = "SEMINAR";
    $("roomEquipment").value = "";
    $("roomStatus").value = "1";
}

async function saveRoom(event) {
    event.preventDefault();
    try {
        if (!isAdmin()) {
            throw new Error("只有管理员可以维护教室");
        }
        const id = $("roomId").value;
        const payload = {
            buildingName: $("roomBuildingName").value.trim(),
            roomNumber: $("roomNumber").value.trim(),
            capacity: Number($("roomCapacity").value || 1),
            roomType: $("roomType").value,
            equipment: $("roomEquipment").value.trim(),
            status: Number($("roomStatus").value)
        };
        await request(id ? `/rooms/${id}` : "/rooms", {
            method: id ? "PUT" : "POST",
            body: JSON.stringify(payload)
        });
        addLog(id ? "教室编辑成功" : "教室新增成功");
        resetRoomForm();
        await loadRooms();
    } catch (error) {
        addLog(error.message, true);
    }
}

async function loadRoomSlots() {
    if (!isAdmin()) {
        return;
    }
    const rows = await request("/admin/room-slots?limit=100");
    state.roomSlots = rows;
    renderRoomSlots();
}

function renderRoomSlots() {
    const body = $("roomSlotsBody");
    if (!body) {
        return;
    }
    const roomName = (roomId) => {
        const room = state.rooms.find((item) => String(item.id) === String(roomId));
        return room ? `${room.buildingName || ""} ${room.roomNumber || ""}` : String(roomId);
    };
    body.innerHTML = state.roomSlots.map((slot) => `<tr>
        <td>${slot.id}</td>
        <td>${escapeHtml(roomName(slot.roomId))}</td>
        <td>${slot.reserveDate || ""}</td>
        <td>${slot.timeSlot || ""}</td>
        <td>${slot.totalCapacity ?? "-"}</td>
        <td>${slot.availableCapacity ?? "-"}</td>
        <td>${slot.reservedCount ?? 0}</td>
        <td>${slot.waitlistCount ?? 0}</td>
        <td>${escapeHtml(slot.openType || "SELF_STUDY")}</td>
        <td>${slotStatusLabel(slot.status)}</td>
        <td class="text-center">
            ${roomSlotActions(slot)}
        </td>
    </tr>`).join("") || `<tr><td colspan="11">暂无 room_slot</td></tr>`;
}

function roomSlotActions(slot) {
    const status = Number(slot.status);
    const reservedCount = Number(slot.reservedCount ?? 0);
    const waitlistCount = Number(slot.waitlistCount ?? 0);
    const hasReservations = reservedCount > 0;
    const hasWaitlist = waitlistCount > 0;
    const hasActiveBusiness = hasReservations || hasWaitlist;
    const expired = status === 4;
    const openDisabled = status === 1 || expired || status === 3;
    const closeDisabled = status === 0 || expired || hasActiveBusiness;
    const maintenanceDisabled = status === 2 || expired || hasActiveBusiness;
    const deleteDisabled = hasActiveBusiness;
    const reservationTip = hasReservations ? `已有 ${reservedCount} 个预约，需先处理预约后才能关闭或维护` : "";
    const waitlistTip = hasWaitlist ? `已有 ${waitlistCount} 个候补，需先取消候补后才能关闭或维护` : "";
    const expiredTip = expired ? "已过期时段不可再修改状态" : "";
    const openTip = expiredTip || (status === 3 ? "教师占用时段不能直接开放" : "当前已经是开放状态");
    const closeTip = reservationTip || waitlistTip || expiredTip || "当前已经是关闭状态";
    const maintenanceTip = reservationTip || waitlistTip || expiredTip || "当前已经是维护状态";
    const deleteTip = reservationTip || waitlistTip || "";
    const note = reservationTip || waitlistTip || expiredTip;

    return `<div class="order-actions">
        ${roomSlotActionButton(slot.id, "open", "开放", "secondary", openDisabled, openDisabled ? openTip : "")}
        ${roomSlotActionButton(slot.id, "close", "关闭", "ghost", closeDisabled, closeDisabled ? closeTip : "")}
        ${roomSlotActionButton(slot.id, "maintenance", "维护", "ghost danger", maintenanceDisabled, maintenanceDisabled ? maintenanceTip : "")}
        ${roomSlotActionButton(slot.id, "delete", "删除", "ghost danger", deleteDisabled, deleteDisabled ? deleteTip : "删除空时段")}
    </div>${note ? `<div class="action-note">${escapeHtml(note)}</div>` : ""}`;
}

function roomSlotActionButton(slotId, action, label, classes, disabled, title) {
    const disabledAttr = disabled ? " disabled" : "";
    const titleAttr = title ? ` title="${escapeHtml(title)}"` : "";
    return `<button class="small ${classes}" data-slot-action="${action}" data-slot-id="${slotId}" type="button"${disabledAttr}${titleAttr}>${label}</button>`;
}

function handleRoomSlotAction(event) {
    const button = event.target.closest("[data-slot-action]");
    if (!button || button.disabled) {
        return;
    }
    if (button.dataset.slotAction === "delete") {
        deleteRoomSlot(button.dataset.slotId);
        return;
    }
    changeRoomSlotStatus(button.dataset.slotId, button.dataset.slotAction);
}

async function saveRoomSlot(event) {
    event.preventDefault();
    try {
        const reserveDate = $("slotDate").value;
        const timeSlot = $("slotTimeSlot").value;
        assertReservableDateTime(reserveDate, timeSlot);
        await request("/admin/room-slots", {
            method: "POST",
            body: JSON.stringify(roomSlotPayload(reserveDate, timeSlot))
        });
        addLog("room_slot 创建成功");
        await Promise.all([loadRoomSlots(), loadStatistics()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function batchCreateRoomSlots() {
    try {
        const startDate = $("slotDate").value;
        const endDate = $("slotEndDate").value || startDate;
        const timeSlot = $("slotTimeSlot").value;
        assertReservableDateTime(startDate, timeSlot);
        if (endDate < startDate) {
            throw new Error("结束日期不能早于开始日期");
        }
        const data = await request("/admin/room-slots/batch", {
            method: "POST",
            body: JSON.stringify({
                roomIds: [Number($("slotRoomSelect").value)],
                startDate,
                endDate,
                timeSlots: [timeSlot],
                capacity: Number($("slotCapacity").value || 1),
                status: Number($("slotStatus").value),
                openType: $("slotOpenType").value
            })
        });
        addLog("room_slot 批量创建完成：" + data);
        await Promise.all([loadRoomSlots(), loadStatistics()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

function roomSlotPayload(reserveDate, timeSlot) {
    return {
        roomId: Number($("slotRoomSelect").value),
        reserveDate,
        timeSlot,
        capacity: Number($("slotCapacity").value || 1),
        status: Number($("slotStatus").value),
        openType: $("slotOpenType").value
    };
}

async function changeRoomSlotStatus(slotId, action) {
    try {
        await request(`/admin/room-slots/${slotId}/${action}`, { method: "PUT" });
        addLog(`room_slot ${slotId} 状态已更新`);
        await Promise.all([loadRoomSlots(), loadStatistics()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function deleteRoomSlot(slotId) {
    if (!window.confirm(`确认删除 room_slot ${slotId}？只能删除没有预约和候补的空时段。`)) {
        return;
    }
    try {
        await request(`/admin/room-slots/${slotId}`, { method: "DELETE" });
        addLog(`room_slot ${slotId} 已删除`);
        await Promise.all([loadRoomSlots(), loadStatistics()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function reconcileRoomSlotCounters() {
    try {
        const updated = await request("/admin/room-slots/reconcile", { method: "POST" });
        addLog(`room_slot 计数已重算：${updated}`);
        await Promise.all([loadRoomSlots(), loadDashboard(), loadStatistics()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function createSubmitToken() {
    try {
        state.submitToken = await request("/reservations/submit-token", { method: "POST" });
        $("submitTokenText").textContent = state.submitToken;
        addLog("一次性提交令牌已生成");
    } catch (error) {
        addLog(error.message, true);
    }
}

async function reserve(event) {
    event.preventDefault();
    try {
        const reserveDate = $("reserveDate").value;
        const timeSlot = $("timeSlot").value;
        assertReservableDateTime(reserveDate, timeSlot);
        if (!state.submitToken) {
            await createSubmitToken();
        }
        const data = await request("/reservations", {
            method: "POST",
            headers: { "X-Submit-Token": state.submitToken },
            body: JSON.stringify({
                roomId: Number($("roomSelect").value),
                reserveDate,
                timeSlot,
                joinWaitlist: $("joinWaitlist").checked
            })
        });
        state.submitToken = "";
        $("submitTokenText").textContent = "已消费";
        addLog("预约提交成功：" + (data.status || "UNKNOWN"));
        await Promise.all([loadOrders(), loadDashboard(), loadNotifications()]);
        navigateTo("orders");
    } catch (error) {
        addLog(error.message, true);
    }
}

async function loadOrders() {
    const [rows, waitlists] = await Promise.all([
        request("/reservations?limit=50"),
        request("/reservations/waitlist?limit=50")
    ]);
    $("ordersBody").innerHTML = rows.map(orderRow).join("") || `<tr><td colspan="7">暂无预约</td></tr>`;
    renderWaitlists(waitlists);
    document.querySelectorAll("[data-action='cancel']").forEach((button) => {
        button.addEventListener("click", () => cancelOrder(button.dataset.orderId));
    });
    document.querySelectorAll("[data-action='sign']").forEach((button) => {
        button.addEventListener("click", () => signOrder(button.dataset.orderId, button.dataset.signCode));
    });
    document.querySelectorAll("[data-action='cancel-waitlist']").forEach((button) => {
        button.addEventListener("click", () => cancelWaitlist(button.dataset.waitlistId));
    });
}

function orderRow(order) {
    const statusText = statusLabel(order.status);
    const canOperate = order.status === 0 || order.status === 1 || (isAdmin() && order.status === 4);
    const signInfo = signWindowInfo(order);
    const signDisabled = isAdmin() || order.status !== 1 || !signInfo.canSign ? "disabled" : "";
    const cancelDisabled = !canOperate ? "disabled" : "";
    const signTitle = !isAdmin() && order.status === 1 ? ` title="${escapeHtml(signInfo.message)}"` : "";
    const signNote = !isAdmin() && order.status === 1 && !signInfo.canSign
        ? `<div class="action-note">${escapeHtml(signInfo.message)}</div>`
        : "";
    return `<tr>
        <td>${order.id}</td>
        <td>${escapeHtml([order.buildingName, order.roomNumber].filter(Boolean).join(" ") || String(order.roomId))}</td>
        <td>${order.reserveDate || ""}</td>
        <td>${order.timeSlot || ""}</td>
        <td>${statusText}</td>
        <td><code>${order.signCode || "-"}</code></td>
        <td>
            <div class="order-actions">
                <button class="small secondary role-user-only" data-action="sign" data-order-id="${order.id}" data-sign-code="${order.signCode || ""}" ${signDisabled}${signTitle}>签到</button>
                <button class="small ghost danger" data-action="cancel" data-order-id="${order.id}" ${cancelDisabled}>取消</button>
            </div>${signNote}
        </td>
    </tr>`;
}

function renderWaitlists(rows) {
    const body = $("waitlistBody");
    if (!body) {
        return;
    }
    body.innerHTML = rows.map(waitlistRow).join("") || `<tr><td colspan="6">\u6682\u65e0\u5019\u8865</td></tr>`;
}

function waitlistRow(item) {
    const statusText = waitlistStatusLabel(item.status);
    const roomName = [item.buildingName, item.roomNumber].filter(Boolean).join(" ") || String(item.roomId);
    const cancelDisabled = item.status === 0 ? "" : "disabled";
    return `<tr>
        <td>${item.id}</td>
        <td>${escapeHtml(roomName)}</td>
        <td>${item.reserveDate || ""}</td>
        <td>${item.timeSlot || ""}</td>
        <td>${statusText}</td>
        <td class="text-center">
            <button class="small ghost danger" data-action="cancel-waitlist" data-waitlist-id="${item.id}" ${cancelDisabled}>\u53d6\u6d88\u5019\u8865</button>
        </td>
    </tr>`;
}

async function cancelOrder(orderId) {
    try {
        await request(`/reservations/${orderId}/cancel`, { method: "POST" });
        addLog("取消成功：" + orderId);
        await Promise.all([loadOrders(), loadDashboard(), loadNotifications()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function cancelWaitlist(waitlistId) {
    try {
        await request(`/reservations/waitlist/${waitlistId}/cancel`, { method: "POST" });
        addLog("\u5019\u8865\u53d6\u6d88\u6210\u529f\uff1a" + waitlistId);
        await Promise.all([loadOrders(), loadDashboard(), loadNotifications()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function signOrder(orderId, signCode) {
    try {
        const code = signCode || window.prompt("请输入签到码");
        if (!code) {
            return;
        }
        await request("/reservations/sign", {
            method: "POST",
            body: JSON.stringify({ orderId: Number(orderId), signCode: code })
        });
        addLog("签到成功：" + orderId);
        await Promise.all([loadOrders(), loadDashboard(), loadNotifications()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function loadFeedbacks() {
    const rows = await request("/feedbacks?limit=50");
    renderFeedbacks(rows);
}

function renderFeedbacks(rows) {
    const body = $("feedbackBody");
    if (!body) {
        return;
    }
    body.innerHTML = rows.map(feedbackRow).join("") || `<tr><td colspan="6">暂无反馈</td></tr>`;
    body.querySelectorAll("[data-action='select-feedback']").forEach((button) => {
        button.addEventListener("click", () => selectFeedback(button.dataset.feedbackId));
    });
    body.querySelectorAll("[data-action='close-feedback']").forEach((button) => {
        button.addEventListener("click", () => closeFeedback(button.dataset.feedbackId));
    });
    body.querySelectorAll("[data-action='analyze-feedback']").forEach((button) => {
        button.addEventListener("click", () => analyzeFeedback(button.dataset.feedbackId));
    });
}

function feedbackRow(item) {
    const student = [item.username, item.phone].filter(Boolean).join(" / ") || String(item.userId);
    const reply = item.adminReply ? escapeHtml(item.adminReply) : "-";
    const action = isAdmin()
        ? `<div class="order-actions"><button class="small secondary" data-action="select-feedback" data-feedback-id="${item.id}" ${item.status === 2 ? "disabled" : ""}>处理</button><button class="small ghost" data-action="analyze-feedback" data-feedback-id="${item.id}">AI 分析</button></div>`
        : `<button class="small ghost danger" data-action="close-feedback" data-feedback-id="${item.id}" ${item.status === 2 ? "disabled" : ""}>关闭</button>`;
    return `<tr>
        <td>${item.id}</td>
        <td>${escapeHtml(student)}</td>
        <td><strong>${escapeHtml(item.title || "")}</strong><p class="cell-muted">${escapeHtml(item.content || "")}</p></td>
        <td>${feedbackStatusLabel(item.status)}</td>
        <td>${reply}</td>
        <td class="text-center">${action}</td>
    </tr>`;
}

async function analyzeFeedback(id) {
    try {
        const analysis = await request(`/agent/feedbacks/${id}/analyze`, { method: "POST" });
        $("feedbackReplyId").value = id;
        $("feedbackReplyText").value = analysis.suggestedReply || "";
        addLog(`工单 ${id} 已由 Agent 分类：${analysis.category}/${analysis.priority}，请人工复核后发送回复`);
        window.alert(`分类：${analysis.category}\n优先级：${analysis.priority}\n摘要：${analysis.summary}\n\n建议回复已回填，必须由管理员人工确认后发送。`);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function submitFeedback(event) {
    event.preventDefault();
    try {
        const title = $("feedbackTitle").value.trim();
        const content = $("feedbackContent").value.trim();
        if (!title || !content) {
            throw new Error("请填写反馈标题和问题描述");
        }
        await request("/feedbacks", {
            method: "POST",
            body: JSON.stringify({ title, content })
        });
        $("feedbackForm").reset();
        addLog("反馈提交成功");
        await Promise.all([loadFeedbacks(), loadNotifications()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

function selectFeedback(id) {
    const row = $("feedbackBody").querySelector(`[data-feedback-id="${id}"]`);
    $("feedbackReplyId").value = id;
    $("feedbackReplyText").value = "";
    if (row) {
        $("feedbackReplyText").focus();
    }
}

async function replyFeedback(event) {
    event.preventDefault();
    try {
        const id = $("feedbackReplyId").value;
        const reply = $("feedbackReplyText").value.trim();
        if (!id) {
            throw new Error("请先选择要处理的反馈");
        }
        if (!reply) {
            throw new Error("请填写回复内容");
        }
        await request(`/feedbacks/${id}/reply`, {
            method: "POST",
            body: JSON.stringify({ reply })
        });
        resetFeedbackReplyForm();
        addLog("反馈回复成功：" + id);
        await Promise.all([loadFeedbacks(), loadNotifications()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

async function closeSelectedFeedback() {
    const id = $("feedbackReplyId").value;
    if (!id) {
        addLog("请先选择要关闭的反馈", true);
        return;
    }
    await closeFeedback(id);
    resetFeedbackReplyForm();
}

async function closeFeedback(id) {
    try {
        await request(`/feedbacks/${id}/close`, { method: "POST" });
        addLog("反馈已关闭：" + id);
        await Promise.all([loadFeedbacks(), loadNotifications()]);
    } catch (error) {
        addLog(error.message, true);
    }
}

function resetFeedbackReplyForm() {
    $("feedbackReplyId").value = "";
    $("feedbackReplyText").value = "";
}

async function recommend(event) {
    event.preventDefault();
    try {
        renderRecommendationMessage("正在生成推荐...");
        const reserveDate = $("advisorDate").value;
        const timeSlot = $("advisorTimeSlot").value;
        assertReservableDateTime(reserveDate, timeSlot);
        const data = await request("/advisor/recommend", {
            method: "POST",
            body: JSON.stringify({
                reserveDate,
                timeSlot,
                expectedCapacity: Number($("expectedCapacity").value || 1),
                buildingName: $("buildingName").value.trim() || null
            })
        });
        renderRecommendations(data);
        addLog("推荐已生成：" + data.length + " 条");
    } catch (error) {
        renderRecommendationMessage(error.message, true);
        addLog(error.message, true);
    }
}

async function askAgent(event) {
    event.preventDefault();
    try {
        const message = $("agentMessage").value.trim();
        if (!message) {
            throw new Error("请描述你的教室需求");
        }
        $("agentAnswer").className = "agent-answer loading";
        $("agentAnswer").textContent = "正在调用受控查询工具...";
        const data = await request("/agent/chat", {
            method: "POST",
            body: JSON.stringify({ message, sessionId: "web-" + (state.user ? state.user.userId : "anonymous") })
        });
        renderAgentResponse(data);
        addLog(`Agent 分析完成：${data.intent || "UNKNOWN"}`);
    } catch (error) {
        $("agentAnswer").className = "agent-answer error-state";
        $("agentAnswer").textContent = error.message;
        addLog(error.message, true);
    }
}

function renderAgentResponse(data) {
    const modeLabels = {
        HYBRID_RAG_TOOL_AGENT: "混合 RAG 工具编排",
        LANGCHAIN4J_GROUNDED_RAG: "LangChain4j 单次检索 RAG",
        SAFETY_GUARD: "安全边界拦截"
    };
    $("agentMode").textContent = modeLabels[data.mode] || data.mode || "已完成";
    $("agentAnswer").className = "agent-answer";
    $("agentAnswer").textContent = data.reply || "暂无回复";
    const candidates = Array.isArray(data.candidates) ? data.candidates : [];
    $("agentCandidates").innerHTML = candidates.map((item) => `<article class="recommend-item">
        <h4>${escapeHtml(`${item.buildingName || ""} ${item.roomNumber || ""}`)}<span class="badge">余 ${item.availableCapacity ?? 0}</span></h4>
        <p>${escapeHtml(`${item.reserveDate || ""} ${item.timeSlot || ""} · 容量 ${item.capacity || "-"} · ${item.equipment || "无设备信息"}`)}</p>
        <p>${escapeHtml(item.reason || "开放时段")}</p>
        <button class="small secondary" type="button" data-action="use-agent-candidate" data-room-id="${item.roomId}" data-date="${item.reserveDate || ""}" data-time-slot="${item.timeSlot || ""}">填写预约表单</button>
    </article>`).join("");
    const stats = data.statistics || {};
    const statsEntries = Object.entries(stats).filter(([, value]) => typeof value === "string" || typeof value === "number");
    $("agentStatistics").innerHTML = statsEntries.map(([key, value]) => `<span><strong>${escapeHtml(key)}</strong>${escapeHtml(String(value))}</span>`).join("");
    const traces = Array.isArray(data.toolTraces) ? data.toolTraces : [];
    $("agentTrace").innerHTML = traces.map((trace) => `<span>${escapeHtml(trace.toolName || "tool")} · ${escapeHtml(trace.summary || "")} · ${trace.durationMs || 0}ms</span>`).join("");
    const sources = Array.isArray(data.sources) ? data.sources : [];
    $("agentSources").innerHTML = sources.map((source) => `<article><strong>来源：${escapeHtml(source.title || "知识库")}</strong><span>${escapeHtml(source.excerpt || "")}</span><small>${escapeHtml(agentKnowledgeCategoryLabel(source.category))} · 匹配分 ${source.score ?? 0}</small></article>`).join("");
    const nextActions = Array.isArray(data.nextActions) ? data.nextActions : [];
    $("agentNextActions").innerHTML = nextActions.map((action) => `<span>${escapeHtml(action)}</span>`).join("");
}

async function saveAgentKnowledge(event) {
    event.preventDefault();
    try {
        const data = await request("/agent/knowledge", {
            method: "POST",
            body: JSON.stringify({
                title: $("agentKnowledgeTitle").value.trim(),
                category: $("agentKnowledgeCategory").value,
                content: $("agentKnowledgeContent").value.trim()
            })
        });
        $("agentKnowledgeForm").reset();
        addLog(`Agent 知识文档已索引：${data.title}`);
        await loadAgentKnowledge();
    } catch (error) {
        addLog(error.message, true);
    }
}

async function loadAgentKnowledge() {
    if (!isAdmin()) return;
    const rows = await request("/agent/knowledge?limit=30");
    $("agentKnowledgeList").className = "agent-admin-list";
    $("agentKnowledgeList").innerHTML = rows.map((item) => `<article><strong>${escapeHtml(item.title || "")}</strong><span>${escapeHtml(agentKnowledgeCategoryLabel(item.category))} · ${escapeHtml(item.sourceType || "TEXT")} · ${escapeHtml(item.indexStatus || "PENDING")} · 切片 ${item.chunkCount || 0} · 向量 ${item.vectorCount || 0}</span><small>${escapeHtml(item.sourceFileName || (item.content || "").slice(0, 180))}${item.lastIndexError ? ` · ${escapeHtml(item.lastIndexError)}` : ""}</small><button class="small secondary" type="button" data-action="remove-agent-knowledge" data-document-id="${item.id}">删除</button></article>`).join("") || "暂无知识文档";
    const status = await request("/agent/knowledge/status");
    $("agentKnowledgeStatus").className = "agent-admin-list";
    $("agentKnowledgeStatus").innerHTML = `<article><strong>检索运行状态</strong><span>向量数据库：${escapeHtml(status.vectorDatabase || "未启用")} · Collection：${escapeHtml(status.vectorCollection || "-")} · 嵌入模型：${status.embeddingEnabled ? "已启用" : "未启用"}</span><small>降级策略：${escapeHtml(status.retrievalFallback || "关键词检索")}</small></article>`;
}

async function uploadAgentKnowledge(event) {
    event.preventDefault();
    try {
        const file = $("agentKnowledgeFile").files[0];
        if (!file) throw new Error("请先选择知识文件");
        const form = new FormData();
        form.append("file", file);
        form.append("title", $("agentKnowledgeUploadTitle").value.trim());
        form.append("category", $("agentKnowledgeUploadCategory").value);
        const data = await request("/agent/knowledge/upload", { method: "POST", body: form });
        $("agentKnowledgeUploadForm").reset();
        addLog(`知识文件已入库：${data.title}，切片 ${data.chunkCount || 0}，向量 ${data.vectorCount || 0}`);
        await loadAgentKnowledge();
    } catch (error) { addLog(error.message, true); }
}

async function rebuildAgentKnowledge() {
    try {
        const count = await request("/agent/knowledge/rebuild", { method: "POST" });
        addLog(`已重新切片并建立 ${count} 份知识文档的索引`);
        await loadAgentKnowledge();
    } catch (error) { addLog(error.message, true); }
}

async function removeAgentKnowledge(event) {
    const button = event.target.closest("[data-action='remove-agent-knowledge']");
    if (!button) return;
    if (!window.confirm("删除后将清理该文档的分块、向量和已上传源文件，确定继续吗？")) return;
    try {
        await request(`/agent/knowledge/${button.dataset.documentId}`, { method: "DELETE" });
        addLog("Agent 知识文档已删除");
        await loadAgentKnowledge();
    } catch (error) { addLog(error.message, true); }
}

async function loadAgentTraces() {
    if (!isAdmin()) return;
    const rows = await request("/agent/traces?limit=20");
    $("agentTraceList").className = "agent-admin-list";
    $("agentTraceList").innerHTML = rows.map((item) => {
        const stages = `检索 ${item.retrievalMs ?? "-"}ms · 精排 ${item.rerankMs ?? "-"}ms · 生成 ${item.generationMs ?? "-"}ms`;
        const usage = `Token ${item.totalTokens ?? 0}${item.estimatedCost == null ? "" : ` · 估算 ${item.estimatedCost} ${item.costCurrency || ""}`}`;
        return `<article class="${item.success === 1 ? "" : "error"}"><strong>${escapeHtml(item.intent || "UNKNOWN")} · ${item.durationMs || 0}ms</strong><span>${escapeHtml(item.inputSummary || "")}</span><small>${escapeHtml(item.modelNames || "无外部模型")} · ${escapeHtml(stages)} · ${escapeHtml(usage)}</small><small>${escapeHtml(item.traceId || "")} · ${escapeHtml(item.createTime || "")}</small></article>`;
    }).join("") || "暂无 Agent Trace";
}

function useAgentCandidate(event) {
    const button = event.target.closest("[data-action='use-agent-candidate']");
    if (!button) {
        return;
    }
    $("roomSelect").value = button.dataset.roomId;
    $("reserveDate").value = button.dataset.date;
    $("timeSlot").value = button.dataset.timeSlot;
    updateTimeSlotOptions("reserveDate", "timeSlot");
    state.submitToken = "";
    $("submitTokenText").textContent = "请在提交前生成";
    addLog("Agent 候选已填入预约表单，请确认后提交");
    navigateTo("reserve");
}

function renderRecommendationMessage(message, error = false) {
    const box = $("recommendList");
    box.className = error ? "recommend-list empty error-state" : "recommend-list empty";
    box.textContent = message;
}

function renderRecommendations(items) {
    const box = $("recommendList");
    if (!items.length) {
        renderRecommendationMessage("暂无推荐");
        return;
    }
    box.className = "recommend-list";
    box.innerHTML = items.map((item) => `<div class="recommend-item">
        <h4>${escapeHtml(item.buildingName)} ${escapeHtml(item.roomNumber)} <span class="badge">${item.matchScore} 分</span></h4>
        <p>容量 ${item.capacity}，热度 ${item.heatScore}，${item.available ? "当前可预约" : "当前紧张"}。${escapeHtml(item.reason || "")}</p>
        <div class="item-actions">
            <button class="small secondary" data-room-id="${item.roomId}" type="button">选择该教室</button>
        </div>
    </div>`).join("");
    box.querySelectorAll("button[data-room-id]").forEach((button) => {
        button.addEventListener("click", () => {
            $("roomSelect").value = button.dataset.roomId;
            $("reserveDate").value = $("advisorDate").value;
            $("timeSlot").value = $("advisorTimeSlot").value;
            addLog("已回填推荐教室：" + button.dataset.roomId);
            navigateTo("reserve");
        });
    });
}

async function loadStock() {
    const roomId = Number($("roomSelect").value || 1);
    const reserveDate = $("reserveDate").value;
    const timeSlot = $("timeSlot").value;
    assertReservableDateTime(reserveDate, timeSlot);
    const params = new URLSearchParams({ roomId, reserveDate, timeSlot });
    const data = await request("/ops/redis/stock?" + params.toString());
    $("stockBox").textContent = JSON.stringify(data, null, 2);
}

async function syncStock() {
    try {
        const reserveDate = $("reserveDate").value;
        const timeSlot = $("timeSlot").value;
        assertReservableDateTime(reserveDate, timeSlot);
        const data = await request("/ops/redis/stock/sync", {
            method: "POST",
            body: JSON.stringify({
                roomId: Number($("roomSelect").value || 1),
                reserveDate,
                timeSlot
            })
        });
        $("stockBox").textContent = JSON.stringify(data, null, 2);
        addLog("库存同步完成");
    } catch (error) {
        addLog(error.message, true);
    }
}

async function clearDemoKeys() {
    try {
        await request("/ops/redis/demo-keys", { method: "DELETE" });
        addLog("演示 Redis Key 已清理");
        await loadRedisOverview();
    } catch (error) {
        addLog(error.message, true);
    }
}

async function loadDashboard() {
    const data = await request("/reservations/dashboard");
    $("successCount").textContent = data.successCount ?? "-";
    $("waitingCount").textContent = data.waitingCount ?? "-";
    $("signedCount").textContent = data.signedCount ?? "-";
    $("cancelCount").textContent = data.cancelCount ?? "-";
    if ($("creditScoreCard")) {
        $("creditScoreCard").textContent = data.creditScore ?? "-";
    }
}

async function loadCredit() {
    if (!isStudent()) {
        return;
    }
    const data = await request("/credits/me?limit=8");
    state.credit = data;
    renderCredit(data);
}

function renderCredit(data) {
    if (!data || !$("creditPanel")) {
        return;
    }
    const account = data.account || {};
    $("creditScoreText").textContent = account.creditScore ?? "-";
    $("violationCountText").textContent = account.violationCount ?? "-";
    const body = $("creditRecords");
    const rows = data.records || [];
    body.innerHTML = rows.map((item) => `<div class="list-item">
        <h4>${escapeHtml(item.reason || "信用变更")} <span class="badge ${item.changeScore >= 0 ? "" : "danger"}">${item.changeScore > 0 ? "+" : ""}${item.changeScore}</span></h4>
        <p>${escapeHtml(item.remark || "")}</p>
        <p class="cell-muted">${item.beforeScore} -> ${item.afterScore} / ${item.createTime || ""}</p>
    </div>`).join("") || `<div class="list empty">暂无信用记录</div>`;
}

async function loadStatistics() {
    if (!isAdmin()) {
        return;
    }
    const data = await request("/ops/statistics/console");
    renderStatistics(data);
}

function renderStatistics(data) {
    if (!$("statisticsOverview")) {
        return;
    }
    $("statisticsOverview").textContent = JSON.stringify(data.outbox || {}, null, 2);
    $("statisticsDashboard").textContent = JSON.stringify(data.dashboard || {}, null, 2);
}

async function loadRedisOverview() {
    const data = await request("/ops/redis/overview");
    $("redisOverview").textContent = JSON.stringify(data, null, 2);
}

async function loadMqOverview() {
    const data = await request("/ops/mq/overview");
    $("mqOverview").textContent = JSON.stringify(data, null, 2);
}

async function loadAuditLogs() {
    const data = await request("/ops/audit/logs?limit=20");
    renderAuditLogs(data);
}

async function loadNotifications() {
    const data = await request("/notifications?limit=10");
    const unread = await request("/notifications/unread-count");
    if ($("unreadCount")) {
        $("unreadCount").textContent = unread.unread ?? 0;
    }
    const box = $("notificationsList");
    if (!data.length) {
        box.className = "message-list empty";
        box.textContent = "暂无通知";
        return;
    }
    box.className = "message-list";
    box.innerHTML = data.map((item) => `<div class="message-item">
        <h4>${escapeHtml(item.title || "通知")}</h4>
        <p>${escapeHtml(item.content || "")}</p>
    </div>`).join("");
}

function connectSse() {
    if (!state.token || state.eventSource) {
        return;
    }
    const base = API_BASE || "";
    const url = `${base}/notifications/stream?token=${encodeURIComponent(state.token)}`;
    const source = new EventSource(url);
    state.eventSource = source;
    source.addEventListener("notification", async (event) => {
        try {
            const item = JSON.parse(event.data);
            addLog("收到实时通知：" + (item.title || "通知"));
            await Promise.all([loadNotifications(), loadDashboard(), isStudent() ? loadCredit() : Promise.resolve()]);
        } catch (error) {
            addLog(error.message, true);
        }
    });
    source.onerror = () => {
        disconnectSse();
        if (state.token) {
            setTimeout(connectSse, 3000);
        }
    };
}

function disconnectSse() {
    if (state.eventSource) {
        state.eventSource.close();
        state.eventSource = null;
    }
}

function statusLabel(status) {
    const map = {
        0: ["待审核", "warn"],
        1: ["已预约", ""],
        3: ["已取消", "danger"],
        4: ["已签到", "muted"]
    };
    const value = map[status] || ["未知", "muted"];
    return `<span class="badge ${value[1]}">${value[0]}</span>`;
}

function waitlistStatusLabel(status) {
    const map = {
        0: ["\u5019\u8865\u4e2d", "warn"],
        1: ["\u5df2\u8865\u4f4d", ""],
        2: ["\u5df2\u53d6\u6d88", "danger"],
        3: ["\u5df2\u8df3\u8fc7", "muted"],
        4: ["\u5df2\u8fc7\u671f", "muted"]
    };
    const value = map[status] || ["\u672a\u77e5", "muted"];
    return `<span class="badge ${value[1]}">${value[0]}</span>`;
}

function slotStatusLabel(status) {
    const map = {
        0: ["关闭", "muted"],
        1: ["开放", ""],
        2: ["维护", "warn"],
        3: ["教师占用", "danger"],
        4: ["过期", "muted"]
    };
    const value = map[status] || ["未知", "muted"];
    return `<span class="badge ${value[1]}">${value[0]}</span>`;
}

function feedbackStatusLabel(status) {
    const map = {
        0: ["待处理", "warn"],
        1: ["已回复", ""],
        2: ["已关闭", "muted"]
    };
    const value = map[status] || ["未知", "muted"];
    return `<span class="badge ${value[1]}">${value[0]}</span>`;
}

function addLog(message, error = false) {
    state.logs.unshift({ message, error, time: new Date().toLocaleTimeString() });
    state.logs = state.logs.slice(0, 12);
    renderLogs();
}

function renderLogs() {
    $("logList").innerHTML = state.logs.map((log) => {
        const cls = log.error ? "log-line error" : "log-line";
        return `<div class="${cls}"><strong>${log.time}</strong> ${escapeHtml(log.message)}</div>`;
    }).join("");
}

function renderAuditLogs(items) {
    const target = $("auditList");
    if (!target) {
        return;
    }
    if (!items.length) {
        target.className = "audit-list empty";
        target.textContent = "暂无后端审计日志";
        return;
    }
    target.className = "audit-list";
    target.innerHTML = items.map((item) => `<div class="audit-item ${item.success === 1 ? "" : "error"}">
        <div>
            <strong>${escapeHtml(item.httpMethod)} ${escapeHtml(item.uri)}</strong>
            <span>${item.httpStatus} / ${item.latencyMs}ms / ${escapeHtml(item.role || "-")}</span>
        </div>
        <code>${escapeHtml(item.traceId)}</code>
    </div>`).join("");
}

function agentKnowledgeCategoryLabel(category) {
    const labels = {
        POLICY: "本系统制度规则",
        GUIDE: "操作指南",
        FAQ: "常见问题",
        EXTERNAL_REFERENCE: "外部高校参考资料",
        EVAL_DATASET: "评测数据集"
    };
    return labels[category] || category || "本系统制度规则";
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

document.addEventListener("DOMContentLoaded", init);
