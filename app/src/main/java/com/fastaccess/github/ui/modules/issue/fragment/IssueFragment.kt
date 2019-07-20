package com.fastaccess.github.ui.modules.issue.fragment

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.SimpleItemAnimator
import com.fastaccess.data.model.ShortUserModel
import com.fastaccess.data.model.TimelineModel
import com.fastaccess.data.model.getEmoji
import com.fastaccess.data.model.parcelable.LabelModel
import com.fastaccess.data.model.parcelable.LoginRepoParcelableModel
import com.fastaccess.data.model.parcelable.MilestoneModel
import com.fastaccess.data.persistence.models.IssueModel
import com.fastaccess.data.persistence.models.LoginModel
import com.fastaccess.data.storage.FastHubSharedPreference
import com.fastaccess.github.R
import com.fastaccess.github.base.BaseFragment
import com.fastaccess.github.base.BaseViewModel
import com.fastaccess.github.base.engine.ThemeEngine
import com.fastaccess.github.extensions.*
import com.fastaccess.github.platform.deeplink.WEB_EDITOR_DEEPLINK
import com.fastaccess.github.platform.mentions.MentionsPresenter
import com.fastaccess.github.ui.adapter.IssueTimelineAdapter
import com.fastaccess.github.ui.modules.issue.fragment.viewmodel.IssueTimelineViewModel
import com.fastaccess.github.ui.modules.issuesprs.edit.LockUnlockFragment
import com.fastaccess.github.ui.modules.issuesprs.edit.assignees.AssigneesFragment
import com.fastaccess.github.ui.modules.issuesprs.edit.labels.LabelsFragment
import com.fastaccess.github.ui.modules.issuesprs.edit.milestone.MilestoneFragment
import com.fastaccess.github.ui.modules.multipurpose.MultiPurposeBottomSheetDialog
import com.fastaccess.github.utils.EXTRA
import com.fastaccess.github.utils.EXTRA_THREE
import com.fastaccess.github.utils.EXTRA_TWO
import com.fastaccess.github.utils.extensions.isConnected
import com.fastaccess.github.utils.extensions.popupEmoji
import com.fastaccess.github.utils.extensions.route
import com.fastaccess.github.utils.extensions.theme
import com.fastaccess.markdown.MarkdownProvider
import com.fastaccess.markdown.spans.LabelSpan
import com.fastaccess.markdown.widget.SpannableBuilder
import com.google.android.material.appbar.AppBarLayout
import com.otaliastudios.autocomplete.Autocomplete
import com.otaliastudios.autocomplete.AutocompleteCallback
import com.otaliastudios.autocomplete.CharPolicy
import github.type.CommentAuthorAssociation
import github.type.IssueState
import github.type.LockReason
import kotlinx.android.synthetic.main.empty_state_layout.*
import kotlinx.android.synthetic.main.issue_header_row_item.*
import kotlinx.android.synthetic.main.issue_pr_fragment_layout.*
import net.nightwhistler.htmlspanner.HtmlSpanner
import timber.log.Timber
import javax.inject.Inject

/**
 * Created by Kosh on 28.01.19.
 */
class IssueFragment : BaseFragment(), LockUnlockFragment.OnLockReasonSelected,
                      LabelsFragment.OnLabelSelected, AssigneesFragment.OnAssigneesSelected,
                      MilestoneFragment.OnMilestoneChanged {

    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var htmlSpanner: HtmlSpanner
    @Inject lateinit var preference: FastHubSharedPreference
    @Inject lateinit var mentionsPresenter: MentionsPresenter

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory).get(IssueTimelineViewModel::class.java) }
    private val login by lazy { arguments?.getString(EXTRA) ?: "" }
    private val repo by lazy { arguments?.getString(EXTRA_TWO) ?: "" }
    private val number by lazy { arguments?.getInt(EXTRA_THREE) ?: 0 }
    private val adapter by lazy { IssueTimelineAdapter(htmlSpanner, preference.theme) }

    override fun layoutRes(): Int = R.layout.issue_pr_fragment_layout
    override fun viewModel(): BaseViewModel? = viewModel

    override fun onFragmentCreatedWithUser(
        view: View,
        savedInstanceState: Bundle?
    ) {
        swipeRefresh.appBarLayout = appBar
        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { p0, p1 ->
            toolbar.menu?.findItem(R.id.scrollTop)?.isVisible = p1 < 0
        })
        setupToolbar("", R.menu.issue_menu)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        recyclerView.setEmptyView(emptyLayout)
        fastScroller.attachRecyclerView(recyclerView, appBar)
        recyclerView.adapter = adapter
        recyclerView.addOnLoadMore { isConnected().isTrue { viewModel.loadData(login, repo, number) } }
        if (savedInstanceState == null) {
            isConnected().isTrue { viewModel.loadData(login, repo, number, true) }
        }
        swipeRefresh.setOnRefreshListener {
            if (isConnected()) {
                recyclerView.resetScrollState()
                viewModel.loadData(login, repo, number, true)
            } else {
                swipeRefresh.isRefreshing = false
            }
        }
        setupEditText()
        observeChanges()
    }

    override fun onDestroyView() {
        mentionsPresenter.onDispose()
        super.onDestroyView()
    }

    override fun onLockReasonSelected(lockReason: LockReason?) {
        viewModel.lockUnlockIssue(login, repo, number, lockReason, true)
    }

    override fun onLabelsSelected(labels: List<LabelModel>?) {
        initLabels(labels)
    }

    override fun onAssigneesSelected(assignees: List<ShortUserModel>?) {
        initAssignees(assignees)
    }

    override fun onMilestoneAdded(
        timeline: TimelineModel,
        milestone: MilestoneModel
    ) {
        viewModel.addTimeline(timeline)
        initMilestone(milestone)
    }

    private fun setupEditText() {
        Autocomplete.on<String>(commentText)
            .with(CharPolicy('@'))
            .with(mentionsPresenter)
            .with(requireContext().getDrawableCompat(R.drawable.popup_window_background))
            .with(object : AutocompleteCallback<String?> {
                override fun onPopupItemClicked(
                    editable: Editable?,
                    item: String?
                ): Boolean {
                    val range = CharPolicy.getQueryRange(editable) ?: return false
                    val start = range[0]
                    val end = range[1]
                    Timber.e("$start $end $item")
                    editable?.replace(start, end, "$item ")
                    return true
                }

                override fun onPopupVisibilityChanged(shown: Boolean) {}
            })
            .build()
        sendComment.setOnClickListener {
            if (viewModel.commentProgress.value == true) return@setOnClickListener
            val comment = commentText.text?.toString()
            if (!comment.isNullOrEmpty()) {
                viewModel.createComment(login, repo, number, comment)
            }
        }
        toggleFullScreen.setOnClickListener {
            it.context.route(WEB_EDITOR_DEEPLINK)
        }
    }

    private fun menuClick(model: IssueModel) {
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.scrollTop -> {
                    appBar.setExpanded(true, true)
                    recyclerView.scrollToPosition(0)
                }
                R.id.refresh -> {
                    viewModel.loadData(login, repo, number, true)
                    appBar.setExpanded(true, true)
                    recyclerView.scrollToPosition(0)
                }
                R.id.closeIssue -> viewModel.closeOpenIssue(login, repo, number)
                R.id.share -> requireActivity().shareUrl(model.url)
                R.id.lockIssue -> if (item.title == getString(R.string.lock_issue)) {
                    MultiPurposeBottomSheetDialog.show(childFragmentManager, MultiPurposeBottomSheetDialog.BottomSheetFragmentType.LOCK_UNLOCK)
                } else {
                    viewModel.lockUnlockIssue(login, repo, number)
                }
                R.id.labels -> MultiPurposeBottomSheetDialog.show(
                    childFragmentManager,
                    MultiPurposeBottomSheetDialog.BottomSheetFragmentType.LABELS, LoginRepoParcelableModel(login, repo, model.labels, number)
                )
                R.id.assignees -> MultiPurposeBottomSheetDialog.show(
                    childFragmentManager,
                    MultiPurposeBottomSheetDialog.BottomSheetFragmentType.ASSIGNEES, LoginRepoParcelableModel(login, repo, model.assignees, number)
                )
                R.id.milestone -> MultiPurposeBottomSheetDialog.show(
                    childFragmentManager,
                    MultiPurposeBottomSheetDialog.BottomSheetFragmentType.MILESTONE, LoginRepoParcelableModel(login, repo, model.assignees, number)
                )
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun observeChanges() {
        viewModel.getIssue(login, repo, number).observeNotNull(this) {
            initIssue(it.first, it.second)
        }
        viewModel.timeline.observeNotNull(this) { timeline ->
            adapter.submitList(timeline)
        }
        viewModel.userNamesLiveData.observeNotNull(this) {
            mentionsPresenter.setUsers(it)
        }
        viewModel.commentProgress.observeNotNull(this) {
            commentProgress.isVisible = it
            sendComment.isVisible = !it
            if (!it) commentText.setText("")
        }
    }

    private fun initIssue(
        model: IssueModel,
        me: LoginModel?
    ) {
        issueHeaderWrapper.isVisible = true
        val theme = preference.theme
        title.text = SpannableBuilder.builder()
            .append(getString(R.string.issue))
            .bold("#${model.number}")
            .newline()
            .append(model.title)
        opener.text = SpannableBuilder.builder()
            .bold(model.author?.login)
            .append(" opened this issue ")
            .append(model.createdAt?.timeAgo())

        userIcon.loadAvatar(model.author?.avatarUrl, model.author?.url ?: "")
        author.text = model.author?.login
        association.text = if (CommentAuthorAssociation.NONE.rawValue() == model.authorAssociation) {
            model.updatedAt?.timeAgo()
        } else {
            "${model.authorAssociation?.toLowerCase()?.replace("_", "")} ${model.updatedAt?.timeAgo()}"
        }
        MarkdownProvider.loadIntoTextView(
            htmlSpanner, description, model.bodyHTML ?: "", ThemeEngine.getCodeBackground(theme),
            ThemeEngine.isLightTheme(theme)
        )
        state.text = model.state?.toLowerCase()
        state.setChipBackgroundColorResource(
            if (IssueState.OPEN.rawValue().equals(model.state, true)) {
                R.color.material_green_700
            } else {
                R.color.material_red_700
            }
        )
        addEmoji.setOnClickListener {
            it.popupEmoji(requireNotNull(model.id), model.reactionGroups) {
                initReactions(model)
            }
        }
        menuClick(model)
        initReactions(model)
        initLabels(model.labels)
        initAssignees(model.assignees)
        initMilestone(model.milestone)
        val isAuthor = login == me?.login || model.authorAssociation?.equals(CommentAuthorAssociation.OWNER.rawValue(), true) == true ||
            model.authorAssociation?.equals(CommentAuthorAssociation.COLLABORATOR.rawValue(), true) == true
        editFab.isVisible = isAuthor
        toolbar.menu.let {
            it.findItem(R.id.edit).isVisible = model.viewerDidAuthor == true
            it.findItem(R.id.assignees).isVisible = isAuthor
            it.findItem(R.id.milestone).isVisible = isAuthor
            it.findItem(R.id.labels).isVisible = isAuthor
            it.findItem(R.id.closeIssue).isVisible = model.viewerDidAuthor == true || isAuthor
            it.findItem(R.id.lockIssue).isVisible = isAuthor
            it.findItem(R.id.closeIssue).title = if (!IssueState.OPEN.rawValue().equals(model.state, true)) {
                getString(R.string.re_open_issue)
            } else {
                getString(R.string.close_issue)
            }
            it.findItem(R.id.lockIssue).title = if (model.locked == true) getString(R.string.unlock_issue) else getString(R.string.lock_issue)
        }
        recyclerView.removeEmptyView()
    }

    private fun initAssignees(assigneesList: List<ShortUserModel>?) {
        assigneesLayout.isVisible = !assigneesList.isNullOrEmpty()
        val builder = SpannableBuilder.builder()
        assigneesList?.forEachIndexed { index, item ->
            builder.clickable("@${item.login ?: item.name ?: ""}", View.OnClickListener {
                it.context.route(item.url)
            }).append(if (index == assigneesList.size.minus(1)) "" else ", ")
        }
        assignees.text = builder
    }

    private fun initMilestone(model: MilestoneModel?) {
        model?.let {
            milestoneLayout.isVisible = true
            milestone.text = when {
                it.title != null -> "${it.title}"
                it.description != null -> "${it.description}"
                else -> ""
            }
        } ?: run { milestoneLayout.isVisible = false }
    }

    private fun initLabels(labelList: List<LabelModel>?) {
        labelsLayout.isVisible = !labelList.isNullOrEmpty()
        val builder = SpannableBuilder.builder()
        labelList?.forEach {
            builder.append(it.name ?: "", LabelSpan(Color.parseColor("#${it.color}")))
                .append(" ")
        }
        labels.text = builder
    }

    private fun initReactions(model: IssueModel) {
        reactionsText.isVisible = model.reactionGroups?.any { it.users?.totalCount != 0 } ?: false
        if (reactionsText.isVisible) {
            val stringBuilder = StringBuilder()
            model.reactionGroups?.forEach {
                if (it.users?.totalCount != 0) {
                    stringBuilder.append(it.content.getEmoji())
                        .append(" ")
                        .append("${it.users?.totalCount}")
                        .append("   ")
                }
            }
            reactionsText.text = stringBuilder
        } else {
            reactionsText.text = ""
        }
    }

    companion object {
        const val TAG = "IssueFragment"
        fun newInstance(
            login: String,
            repo: String,
            number: Int
        ) = IssueFragment().apply {
            arguments = bundleOf(EXTRA to login, EXTRA_TWO to repo, EXTRA_THREE to number)
        }
    }
}