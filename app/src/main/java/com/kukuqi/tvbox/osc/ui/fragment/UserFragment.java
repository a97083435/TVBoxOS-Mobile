package com.kukuqi.tvbox.osc.ui.fragment;

import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.kukuqi.tvbox.osc.R;
import com.kukuqi.tvbox.osc.api.ApiConfig;
import com.kukuqi.tvbox.osc.base.BaseLazyFragment;
import com.kukuqi.tvbox.osc.bean.Movie;
import com.kukuqi.tvbox.osc.ui.activity.DetailActivity;
import com.kukuqi.tvbox.osc.ui.activity.FastSearchActivity;

import com.kukuqi.tvbox.osc.ui.adapter.GridAdapter;
import com.kukuqi.tvbox.osc.util.Utils;


import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment {

    private android.widget.TextView recommendationStatus;
    private GridAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    RecyclerView tvHotList1;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    private com.kukuqi.tvbox.osc.viewmodel.SourceViewModel recommendations;

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();

        tvHotList1.setHasFixedSize(true);
        if (tvHotList1.getLayoutManager() == null) tvHotList1.setLayoutManager(new GridLayoutManager(this.mContext, Utils.getPosterSpanCount(this.mContext)));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (tvHotList1 != null) {
            tvHotList1.setLayoutManager(new GridLayoutManager(this.mContext, Utils.getPosterSpanCount(this.mContext)));
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        tvHotList1 = findViewById(R.id.tvHotList1);
        recommendationStatus = findViewById(R.id.homeRecommendationStatus);
        recommendationStatus.setOnClickListener(v -> refreshContent());
        swipeRefresh = findViewById(R.id.swipeRefresh);
        BrowseControls.attach(this, swipeRefresh, findViewById(R.id.btn_live), tvHotList1,
                () -> tvHotList1, this::refreshContent, null);
        recommendations = new androidx.lifecycle.ViewModelProvider(this).get(com.kukuqi.tvbox.osc.viewmodel.SourceViewModel.class);
        recommendations.sortResult.observe(this, result -> {
            homeSourceRec = result == null ? null : result.videoList;
            initHomeHotVod(homeHotVodAdapter);
        });
        homeHotVodAdapter = new GridAdapter();
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty()){
                    ToastUtils.showShort("暂无订阅");
                    return;
                }
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                if (com.kukuqi.tvbox.osc.util.SourceActions.run(UserFragment.this, vod, UserFragment.this::refreshContent)) return;
                if (com.kukuqi.tvbox.osc.util.SourceActions.folder(requireContext(), vod)) return;
                Bundle bundle = new Bundle();
                if (!TextUtils.isEmpty(vod.id)) {
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    jumpActivity(DetailActivity.class, bundle);
                } else {
                    bundle.putString("title", vod.name);
                    jumpActivity(FastSearchActivity.class, bundle);
                }
            }
        });

        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty()) return true;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                Bundle bundle = new Bundle();
                bundle.putString("title", vod.name);
                jumpActivity(FastSearchActivity.class, bundle);
                return true;
            }
        });

        tvHotList1.setAdapter(homeHotVodAdapter);
        setLoadSir2(tvHotList1);
        initHomeHotVod(homeHotVodAdapter);
    }

    private void initHomeHotVod(GridAdapter adapter) {
        boolean available = homeSourceRec != null && !homeSourceRec.isEmpty();
        adapter.setNewData(available ? homeSourceRec : new ArrayList<>());
        if (available) showSuccess(); else showEmpty();
        status(available ? "" : "当前站点没有提供首页推荐，可更换站点；点击重试");
        swipeRefresh.setRefreshing(false);
    }
    @Override public void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == com.kukuqi.tvbox.osc.util.SourceActions.WEB_RESULT) refreshContent();
    }

    private void status(String message) {
        recommendationStatus.setText(message);
        recommendationStatus.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void refreshContent() {
        if (ApiConfig.get().getHomeSourceBean() == null) {
            status("请先添加订阅并选择站点");
            swipeRefresh.setRefreshing(false);
            return;
        }
        recommendations.getSort(ApiConfig.get().getHomeSourceBean().getKey());
    }
}
